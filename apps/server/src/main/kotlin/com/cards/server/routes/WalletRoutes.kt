package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ApplyOutcome
import com.dangerfield.cards.server.domain.RewardChips
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserMessageKind
import com.dangerfield.cards.server.domain.UserMessageRepository
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.WALLET_WRITE_LIMIT
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.UUID
import kotlin.time.ExperimentalTime

/**
 * Server-authoritative chip wallet endpoints.
 *
 * `GET /v1/me/wallet` returns the caller's current chip balance,
 * lazy-creating the wallet row (with the starter grant) if this is the
 * user's first contact.
 *
 * `POST /v1/me/wallet/sync` accepts a batch of locally-applied chip
 * events and reconciles them with the server's ledger. The batch is
 * applied in order; each event is idempotent on its
 * `idempotencyKey`. A delta that would push the balance below zero is
 * rejected with [WalletEventOutcomeDto.InsufficientChips] — the wallet
 * stays put, the batch continues, and the client surfaces a soft
 * reconcile message.
 *
 * Reward credits are server-owned (ENG-9): a positive delta whose
 * reason is a `levelup.*` / `achievement.*` reward is refused with
 * [WalletEventOutcomeDto.RefusedServerOwned] without touching the
 * ledger. The server grants those itself — level chips on progression
 * sync, achievement chips on earned-achievement sync — from
 * [RewardChips], so a modified client can't pick its own amounts.
 *
 * Soft bust protection: both endpoints, after their normal work, call
 * [maybeApplyBustProtection]. The first time the user's balance hits
 * zero (and only the first time, ever — keyed off
 * [Wallet.BUST_PROTECTION_KEY]) the server grants
 * [Wallet.BUST_PROTECTION_GRANT] chips and queues a "Welcome back to
 * the table." dialog. Idempotency on the wallet ledger collapses
 * subsequent zero-balance reads to a no-op, so it's safe to call from
 * any endpoint that learns about the balance.
 *
 * Both endpoints require a valid Supabase JWT. The userId comes from
 * the `sub` claim; the client never passes it in the body. Per-IP rate
 * limit on the sync endpoint protects against runaway clients
 * inadvertently DoSing themselves and against trivial abuse.
 */
@OptIn(ExperimentalTime::class)
fun Route.walletRoutes(
    repository: WalletRepository,
    messages: UserMessageRepository,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/wallet") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val initial = repository.findOrCreateResult(userId)
            val balance = maybeApplyBustProtection(
                userId = userId,
                currentBalance = initial.wallet.balance,
                wallets = repository,
                messages = messages,
            )
            call.respond(
                HttpStatusCode.OK,
                WalletResponse(balance = balance, walletCreated = initial.created),
            )
        }

        rateLimit(RateLimitName(WALLET_WRITE_LIMIT)) {
            post("/v1/me/wallet/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<WalletSyncRequest>()

                val initial = repository.findOrCreateResult(userId)
                var lastBalance = initial.wallet.balance
                val results = body.events.map { event ->
                    if (RewardChips.isServerOwnedRewardCredit(delta = event.delta, reason = event.reason)) {
                        return@map WalletEventResultDto(
                            idempotencyKey = event.idempotencyKey,
                            outcome = WalletEventOutcomeDto.RefusedServerOwned,
                            balance = lastBalance,
                            message = "Reward credits are granted server-side; this event was ignored.",
                        )
                    }
                    val outcome = repository.apply(
                        userId = userId,
                        idempotencyKey = event.idempotencyKey,
                        delta = event.delta,
                        reason = event.reason,
                    )
                    lastBalance = outcome.balance
                    when (outcome) {
                        is ApplyOutcome.Applied -> WalletEventResultDto(
                            idempotencyKey = event.idempotencyKey,
                            outcome = if (outcome.wasAlreadyApplied) {
                                WalletEventOutcomeDto.AlreadyApplied
                            } else {
                                WalletEventOutcomeDto.Applied
                            },
                            balance = outcome.balance,
                        )

                        is ApplyOutcome.InsufficientChips -> WalletEventResultDto(
                            idempotencyKey = event.idempotencyKey,
                            outcome = WalletEventOutcomeDto.InsufficientChips,
                            balance = outcome.balance,
                            message = "Not enough chips to apply this debit.",
                        )
                    }
                }

                lastBalance = maybeApplyBustProtection(
                    userId = userId,
                    currentBalance = lastBalance,
                    wallets = repository,
                    messages = messages,
                )

                call.respond(
                    HttpStatusCode.OK,
                    WalletSyncResponse(
                        balance = lastBalance,
                        results = results,
                        walletCreated = initial.created,
                    ),
                )
            }
        }
    }
}

/**
 * If [currentBalance] is exactly zero, attempt the soft-bust-protection
 * grant via [WalletRepository.apply] with the lifetime-once
 * [Wallet.BUST_PROTECTION_KEY]. Returns the post-grant balance, which
 * is the same as [currentBalance] when the user is non-zero or has
 * already used their lifetime bust-protection grant.
 *
 * On the *first* grant (not on idempotent replays), also enqueues a
 * Dialog [UserMessage] so the next foreground / cold-boot shows the
 * "Welcome back to the table." copy. Mirrors the AdminRoutes pattern
 * of attaching a message only when the grant actually moved chips.
 */
@OptIn(ExperimentalTime::class)
private suspend fun maybeApplyBustProtection(
    userId: UserId,
    currentBalance: Long,
    wallets: WalletRepository,
    messages: UserMessageRepository,
): Long {
    if (currentBalance != 0L) return currentBalance

    val outcome = wallets.apply(
        userId = userId,
        idempotencyKey = Wallet.BUST_PROTECTION_KEY,
        delta = Wallet.BUST_PROTECTION_GRANT,
        reason = Wallet.BUST_PROTECTION_REASON,
    )
    if (outcome is ApplyOutcome.Applied && !outcome.wasAlreadyApplied) {
        messages.create(
            id = UUID.randomUUID(),
            userId = userId,
            idempotencyKey = "${Wallet.BUST_PROTECTION_KEY}_msg",
            kind = UserMessageKind.Dialog,
            emoji = "💰",
            title = "Welcome back to the table.",
            body = "Your chips ran out — here's ${Wallet.BUST_PROTECTION_GRANT} on the house. Play smart.",
            deepLink = null,
            expiresAt = null,
        )
    }
    return outcome.balance
}

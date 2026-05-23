package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ApplyOutcome
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
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
 * Welcome-week grants: every wallet contact also runs through
 * [maybeApplyWelcomeWeek], which silently applies the daily
 * [Wallet.WELCOME_WEEK_DAILY_GRANT] for each eligible day since the
 * wallet's `createdAt` (capped at [Wallet.WELCOME_WEEK_DAYS]). Each
 * day is keyed independently for idempotency, so missed days are
 * still granted next time the user opens the app — spec calls for
 * "no streak, no expiry, no 'you missed yesterday' copy."
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
    clock: Clock,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/wallet") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val initial = repository.findOrCreate(userId)
            val afterWelcome = maybeApplyWelcomeWeek(
                userId = userId,
                walletCreatedAt = initial.createdAt,
                currentBalance = initial.balance,
                wallets = repository,
                clock = clock,
            )
            val balance = maybeApplyBustProtection(
                userId = userId,
                currentBalance = afterWelcome,
                wallets = repository,
                messages = messages,
            )
            call.respond(HttpStatusCode.OK, WalletResponse(balance = balance))
        }

        rateLimit(RateLimitName(WALLET_WRITE_LIMIT)) {
            post("/v1/me/wallet/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<WalletSyncRequest>()

                val initial = repository.findOrCreate(userId)
                var lastBalance = maybeApplyWelcomeWeek(
                    userId = userId,
                    walletCreatedAt = initial.createdAt,
                    currentBalance = initial.balance,
                    wallets = repository,
                    clock = clock,
                )
                val results = body.events.map { event ->
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
                    WalletSyncResponse(balance = lastBalance, results = results),
                )
            }
        }
    }
}

/**
 * Apply the welcome-week daily grant for every elapsed day in
 * `[WELCOME_WEEK_FIRST_DAY, min(daysSinceCreatedAt, WELCOME_WEEK_LAST_DAY)]`
 * that hasn't been granted yet. Signup day (elapsed day 0) is
 * intentionally skipped — the user already gets the [Wallet.STARTER_GRANT]
 * on first contact, so layering a daily bonus on top of that on
 * the same day muddies the "here's your starter chips" moment.
 * The daily +500 kicks in the day after signup and runs for
 * [Wallet.WELCOME_WEEK_DAYS] consecutive days.
 *
 * Each day uses a stable per-(user, day) idempotency key so re-
 * applying already-granted days is a no-op via
 * [WalletRepository.apply]'s replay detection.
 *
 * Why iterate every day rather than just "today's" day: the spec
 * says no expiry and no "you missed yesterday" copy. A user who
 * skips a day still gets that day's chips on their next open. The
 * apply path short-circuits on the existing idempotency key, so the
 * additional cost is at most [Wallet.WELCOME_WEEK_DAYS] reads —
 * cheap enough to do unconditionally on every wallet contact.
 *
 * Returns the post-grant balance. Equal to [currentBalance] when
 * the user is on signup day, past their welcome week, or every
 * eligible day has already been applied.
 */
@OptIn(ExperimentalTime::class)
private suspend fun maybeApplyWelcomeWeek(
    userId: UserId,
    walletCreatedAt: Instant,
    currentBalance: Long,
    wallets: WalletRepository,
    clock: Clock,
): Long {
    val elapsedDays = (clock.now() - walletCreatedAt).inWholeDays
        .coerceAtLeast(0L)
        .toInt()
    val lastEligibleDay = elapsedDays.coerceAtMost(Wallet.WELCOME_WEEK_LAST_DAY)
    if (lastEligibleDay < Wallet.WELCOME_WEEK_FIRST_DAY) return currentBalance
    var balance = currentBalance
    for (day in Wallet.WELCOME_WEEK_FIRST_DAY..lastEligibleDay) {
        val outcome = wallets.apply(
            userId = userId,
            idempotencyKey = "${Wallet.WELCOME_WEEK_KEY_PREFIX}${day}_v1",
            delta = Wallet.WELCOME_WEEK_DAILY_GRANT,
            reason = Wallet.WELCOME_WEEK_REASON,
        )
        balance = outcome.balance
    }
    return balance
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

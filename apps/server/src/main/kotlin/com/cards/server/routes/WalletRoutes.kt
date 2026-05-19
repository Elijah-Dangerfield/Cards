package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ApplyOutcome
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
 * Both endpoints require a valid Supabase JWT. The userId comes from
 * the `sub` claim; the client never passes it in the body. Per-IP rate
 * limit on the sync endpoint protects against runaway clients
 * inadvertently DoSing themselves and against trivial abuse.
 */
fun Route.walletRoutes(repository: WalletRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        get("/v1/me/wallet") {
            val userId = call.userId() ?: return@get call.respond(HttpStatusCode.Unauthorized)
            val wallet = repository.findOrCreate(userId)
            call.respond(HttpStatusCode.OK, WalletResponse(balance = wallet.balance))
        }

        rateLimit(RateLimitName(WALLET_WRITE_LIMIT)) {
            post("/v1/me/wallet/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<WalletSyncRequest>()

                var lastBalance = repository.findOrCreate(userId).balance
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

                call.respond(
                    HttpStatusCode.OK,
                    WalletSyncResponse(balance = lastBalance, results = results),
                )
            }
        }
    }
}

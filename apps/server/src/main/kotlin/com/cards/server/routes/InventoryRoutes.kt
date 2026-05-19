package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `POST /v1/inventory/sync` — reconciles a client's locally-pending
 * purchases with the server's authoritative ledger.
 *
 * For each submitted purchase, the server records it idempotently via
 * [InventoryRepository.recordPurchase] (first-purchase-wins on the
 * (userId, productId) key) and echoes [SyncOutcomeDto.Confirmed]. The
 * `Reverted` branch is reserved for the future server-side chip ledger
 * — once spend gets validated, an unaffordable purchase will surface as
 * Reverted with chipsToRefund and the client will credit it back.
 *
 * Empty request is valid: returns an empty result list without touching
 * the DB. Idempotent on the wire: re-syncing the same purchases produces
 * the same response without bumping anything.
 *
 * Requires a valid Supabase JWT. The userId comes from the `sub` claim;
 * clients never pass it in the body.
 */
@OptIn(ExperimentalTime::class)
fun Route.inventoryRoutes(repository: InventoryRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        post("/v1/inventory/sync") {
            val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val request = call.receive<InventorySyncRequest>()
            val results = request.purchases.map { pending ->
                repository.recordPurchase(
                    userId = userId,
                    productId = pending.productId,
                    costChipsAtPurchase = pending.costChipsAtPurchase,
                    purchasedAt = Instant.fromEpochMilliseconds(pending.purchasedAtEpochMs),
                )
                InventorySyncResultDto(
                    productId = pending.productId,
                    outcome = SyncOutcomeDto.Confirmed,
                )
            }
            call.respond(HttpStatusCode.OK, InventorySyncResponse(results = results))
        }
    }
}

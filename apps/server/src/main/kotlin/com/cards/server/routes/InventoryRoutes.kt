package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.http.clientContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * `POST /v1/inventory/sync` — reconcile a client's local pending purchases
 * with the server's authoritative ledger.
 *
 * V1 behavior (pre-auth): no real reconciliation possible — the server has
 * no concept of *who* is purchasing or what their balance is. We echo every
 * submitted purchase back as [SyncOutcomeDto.Confirmed]. The contract is in
 * place so once auth + a per-user ledger lands, the client requires no
 * change: it sees Confirmed/Reverted outcomes uniformly.
 *
 * When auth lands, this endpoint will:
 *  1. Identify the user from the bearer token (already plumbed through
 *     NetworkClient.authenticatedClient).
 *  2. Replay each submitted purchase against the server-side chip ledger.
 *  3. For each: if the user could afford it at the time of purchase, mark
 *     Confirmed. Otherwise mark Reverted + return chipsToRefund.
 *  4. The client trusts the server's response — last-write-wins.
 *
 * Empty request is valid and returns an empty result list. Idempotent on
 * the wire: re-syncing the same set of purchases produces the same response.
 */
fun Route.inventoryRoutes() {
    post("/v1/inventory/sync") {
        // ClientContext lets the future-auth path know the platform / app
        // version / locale of the requester — useful for diagnostics even
        // before we use it for filtering.
        @Suppress("UNUSED_VARIABLE")
        val ctx = call.clientContext()

        val request = call.receive<InventorySyncRequest>()
        val results = request.purchases.map { pending ->
            InventorySyncResultDto(
                productId = pending.productId,
                outcome = SyncOutcomeDto.Confirmed,
                // Real reconciliation lands with auth — for now everything
                // is Confirmed and chipsToRefund stays null.
            )
        }
        call.respond(HttpStatusCode.OK, InventorySyncResponse(results = results))
    }
}

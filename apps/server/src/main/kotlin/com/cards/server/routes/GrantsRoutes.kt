package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.AchievementRewards
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.http.clientContext
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * `POST /v1/me/grants/achievement/{achievementId}`
 *  — record an earned-grant for whatever cosmetic the given client-side
 *  achievement maps to, if any.
 *
 * Responses:
 *  - `200 OK` with [OwnedItemDto] — the achievement carried a reward and
 *    the user now owns (or already owned) it. Body shape mirrors the
 *    inventory-sync owned snapshot so the client can fold the row in
 *    without a parallel deserializer.
 *  - `204 No Content` — no inventory reward for this achievement (the
 *    common case; achievement carried XP / chips only). Also returned
 *    when the achievement id is unknown OR the rewarded product no longer
 *    exists in the catalog, so a client on a newer build than the server
 *    quietly degrades instead of erroring.
 *
 * Idempotent on `(userId, productId)` via
 * [InventoryRepository.recordEarnedGrant] — a duplicate POST returns the
 * existing row. Clients can safely re-fire grants after a sync failure or
 * cold-boot replay without double-granting.
 *
 * **Why a route, not a sync-style batch:** the trigger is the client
 * detecting a fresh unlock at the end of a hand. Posting one
 * achievement id at the moment of unlock keeps the request body trivial,
 * and a 204 means "noted, nothing to do" — useful telemetry for tracking
 * unlock-event coverage without forcing the client to know which
 * achievements have rewards.
 */
@OptIn(ExperimentalTime::class)
fun Route.grantsRoutes(
    inventory: InventoryRepository,
    catalog: ProductCatalogSource,
    clock: Clock = Clock.System,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        post("/v1/me/grants/achievement/{achievementId}") {
            val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val achievementId = call.parameters["achievementId"]
                ?.takeIf { it.isNotBlank() }
                ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    grantsProblem(
                        "invalid_achievement_id",
                        "Achievement id must be non-empty.",
                    ),
                )
            val productId = AchievementRewards.productIdFor(achievementId)
                ?: return@post call.respond(HttpStatusCode.NoContent)
            // Catalog lookup uses readById so unlock-only rows resolve
            // (the read() filter would hide them). If the product was
            // removed from the catalog between client builds, drop the
            // grant rather than 500 — a 204 reads to the client as
            // "no-op, nothing was wrong with your request."
            val product = catalog.readById(productId, call.clientContext())
                ?: return@post call.respond(HttpStatusCode.NoContent)
            val granted = inventory.recordEarnedGrant(
                userId = userId,
                productId = product.id,
                grantedAt = clock.now(),
            )
            call.respond(
                HttpStatusCode.OK,
                OwnedItemDto(
                    productId = granted.productId,
                    costChipsAtPurchase = granted.costChipsAtPurchase,
                    purchasedAtEpochMs = granted.purchasedAt.toEpochMilliseconds(),
                    acquisitionSource = granted.acquisitionSource.wire,
                ),
            )
        }
    }
}

private fun grantsProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

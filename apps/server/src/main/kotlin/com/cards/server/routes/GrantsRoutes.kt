package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ClientGrantableAchievements
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.LevelGrantableProducts
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.http.clientContext
import com.dangerfield.cards.server.plugins.ACHIEVEMENT_GRANT_LIMIT
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
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
 *  - `200 OK` with [OwnedItemDto] — the achievement is on the
 *    [ClientGrantableAchievements] allowlist and the user now owns (or
 *    already owned) the mapped product. Body shape mirrors the
 *    inventory-sync owned snapshot so the client can fold the row in
 *    without a parallel deserializer.
 *  - `204 No Content` — server doesn't recognise this achievement id (a
 *    client on a newer build than the server quietly degrades), or the
 *    achievement is allowlisted but its product is no longer in the
 *    catalog.
 *  - `403 Forbidden` — the achievement is known to the server but is
 *    server-witnessed, so the client has no business granting it. Today
 *    [ClientGrantableAchievements.Default] has no server-witnessed
 *    entries; once real-PvP / league achievements ship server-side, they
 *    land in the `serverWitnessed` set and self-grant attempts get 403.
 *
 * Idempotent on `(userId, productId)` via
 * [InventoryRepository.recordEarnedGrant] — a duplicate POST returns the
 * existing row. Clients can safely re-fire grants after a sync failure or
 * cold-boot replay without double-granting.
 *
 * Rate-limited via [ACHIEVEMENT_GRANT_LIMIT] (per-IP). Real clients fire
 * each (user, achievement) once for life — the bucket exists to cap a
 * scripted client from flooding the grants table or our inventory write
 * path, not to throttle legitimate play.
 *
 * `POST /v1/me/grants/level-cosmetic/{productId}`
 *  — the level-up counterpart: record an earned-grant for a cosmetic the
 *  client's level-reward table says it crossed. Gated by
 *  [LevelGrantableProducts] (a product not on the allowlist returns `403`,
 *  not `204`, so a client can't self-grant arbitrary catalog cosmetics).
 *  Shares the achievement route's `200`/`204` semantics and idempotency.
 */
@OptIn(ExperimentalTime::class)
fun Route.grantsRoutes(
    inventory: InventoryRepository,
    catalog: ProductCatalogSource,
    policy: ClientGrantableAchievements = ClientGrantableAchievements.Default,
    levelGrantable: LevelGrantableProducts = LevelGrantableProducts.Default,
    clock: Clock = Clock.System,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        rateLimit(RateLimitName(ACHIEVEMENT_GRANT_LIMIT)) {
            post("/v1/me/grants/level-cosmetic/{productId}") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val productId = call.parameters["productId"]
                    ?.takeIf { it.isNotBlank() }
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        grantsProblem(
                            "invalid_product_id",
                            "Product id must be non-empty.",
                        ),
                    )
                if (!levelGrantable.isGrantable(productId)) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        grantsProblem(
                            "not_level_grantable",
                            "Product '$productId' is not grantable via level-up.",
                        ),
                    )
                }
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
                when (val resolution = policy.resolve(achievementId)) {
                    ClientGrantableAchievements.Resolution.Unknown ->
                        call.respond(HttpStatusCode.NoContent)
                    ClientGrantableAchievements.Resolution.ServerWitnessed ->
                        call.respond(
                            HttpStatusCode.Forbidden,
                            grantsProblem(
                                "server_witnessed_achievement",
                                "Achievement '$achievementId' is granted server-side; clients can't self-grant it.",
                            ),
                        )
                    is ClientGrantableAchievements.Resolution.Grant -> {
                        val product = catalog.readById(resolution.productId, call.clientContext())
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
        }
    }
}

private fun grantsProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

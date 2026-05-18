package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.EquipmentRepository
import com.dangerfield.cards.server.plugins.PROFILE_WRITE_LIMIT
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * `POST /v1/equipment/sync` — single endpoint that does double duty:
 *
 *  - With a non-empty `ops` body, the server applies each op (last-write-
 *    wins by `updatedAtEpochMs`) and returns the resulting equipped set.
 *  - With an empty `ops` body, the server returns the current equipped
 *    set without mutating. Clients use this as the cold-start "what's
 *    actually equipped right now" fetch.
 *
 * Mirrors `POST /v1/inventory/sync`'s shape so the client can use the
 * same retry / single-flight loop. Reuses the existing PROFILE_WRITE
 * rate-limit bucket — equipment changes are profile-style writes from
 * a load-shaping perspective (30/hour/IP is plenty).
 *
 * Requires a valid Supabase JWT. The `userId` comes from the `sub`
 * claim — clients never pass it in the body.
 */
@OptIn(ExperimentalTime::class)
fun Route.equipmentRoutes(repository: EquipmentRepository, clock: Clock = Clock.System) {
    authenticate(SUPABASE_JWT_AUTH) {
        rateLimit(RateLimitName(PROFILE_WRITE_LIMIT)) {
            post("/v1/equipment/sync") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val request = call.receive<EquipmentSyncRequest>()

                // Apply ops in op-timestamp order so a stale "equip"
                // followed by a fresh "unequip" in the same batch ends
                // up Unequipped (and vice versa).
                val orderedOps = request.ops.sortedBy { it.updatedAtEpochMs }
                for (op in orderedOps) {
                    val opTs = Instant.fromEpochMilliseconds(op.updatedAtEpochMs)
                    if (op.equip) {
                        repository.equip(userId, op.productId, opTs)
                    } else {
                        repository.unequip(userId, op.productId, opTs)
                    }
                }

                val nowMs = clock.now().toEpochMilliseconds()
                val current = repository.listEquipped(userId)
                call.respond(
                    HttpStatusCode.OK,
                    EquipmentSyncResponse(
                        serverNowEpochMs = nowMs,
                        equipped = current.map { item ->
                            EquippedItemDto(
                                productId = item.productId,
                                updatedAtEpochMs = item.updatedAt.toEpochMilliseconds(),
                            )
                        },
                    ),
                )
            }
        }
    }
}

package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.MatchmakingResult
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.plugins.MATCHMAKING_FIND_LIMIT
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

/**
 * Public matchmaking — the "Find a Room" backend.
 *
 *  - `POST /v1/matchmaking/find` — body `{ "minBuyIn": …, "maxBuyIn": … }`.
 *    Seats the caller into the best eligible Open/Public table in that buy-in
 *    range, else opens a fresh public table for them. Returns the room (the
 *    client opens its `/socket` next, exactly as for a private room) plus
 *    `created` for the created-vs-joined density metric.
 *
 * No bots are ever seated here — the disclosed bot fallback is a later,
 * consent-gated step. Cancelling a search is just the existing
 * `DELETE /v1/rooms/{code}/me`.
 *
 * The blocked-pair filter is computed *before* [RoomService.findOrJoinPublic]
 * (one indexed friend-graph read) and passed in, so the friend graph is never
 * queried while the rooms mutex is held. Display name + avatar come from the
 * profile so a member can't spoof another's identity via the body.
 *
 * Status codes: 200 happy path · 400 malformed/out-of-range buy-in · 401 no JWT
 * · 429 rate-limited. Abuse fence is per-IP rate limiting ([MATCHMAKING_FIND_LIMIT])
 * plus the empty-room GC that reclaims abandoned tables.
 *
 * Deferred to Phase 4 (escrow), tracked so they're explicit, not forgotten:
 *  - **No wallet check here.** The plan's fast advisory "can you afford this
 *    tier" reject at find() isn't wired yet; the authoritative check is the
 *    sit-down escrow debit. A searcher short on chips is seated and only bounced
 *    at the buy-in. Harmless today (no escrow), revisit when B3 lands.
 *  - **No global public-room cap.** The per-IP rate limit + the GC-on-leave that
 *    reclaims a solo searcher's table before they can stack up another are the
 *    only ceilings on concurrent public rooms. A dedicated global cap (plan
 *    §2.5) is unbuilt; add it if public-room count ever needs a hard bound.
 */
fun Route.matchmakingRoutes(
    rooms: RoomService,
    friends: FriendRepository,
    profiles: ProfileRepository,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        rateLimit(RateLimitName(MATCHMAKING_FIND_LIMIT)) {
            post("/v1/matchmaking/find") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<MatchmakingFindRequest>()

                if (body.minBuyIn > body.maxBuyIn) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        matchmakingProblem("invalid_range", "minBuyIn must be <= maxBuyIn."),
                    )
                }
                if (body.minBuyIn < RoomSettings.MIN_BUY_IN || body.maxBuyIn > RoomSettings.MAX_BUY_IN) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        matchmakingProblem(
                            "invalid_buy_in",
                            "Buy-in range must be within " +
                                "${RoomSettings.MIN_BUY_IN}..${RoomSettings.MAX_BUY_IN}.",
                        ),
                    )
                }

                val profile = profiles.findOrCreate(userId)
                // One indexed read, before the rooms mutex — never query the
                // friend graph under the lock.
                val blocked = friends.listBlockedUserIds(userId)

                val result = rooms.findOrJoinPublic(
                    userId = userId,
                    name = profile.displayName,
                    minBuyIn = body.minBuyIn,
                    maxBuyIn = body.maxBuyIn,
                    blockedUserIds = blocked,
                    avatarEmoji = profile.avatarEmoji,
                    avatarBackgroundColor = profile.avatarBackgroundColor,
                )

                call.respond(
                    HttpStatusCode.OK,
                    MatchmakingFindResponse(
                        room = result.room.toDto(),
                        created = result is MatchmakingResult.Created,
                    ),
                )
            }
        }
    }
}

private fun matchmakingProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

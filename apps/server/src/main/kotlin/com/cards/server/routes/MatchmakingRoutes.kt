package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.EquipmentRepository
import com.dangerfield.cards.server.domain.FriendRepository
import com.dangerfield.cards.server.domain.MatchmakingResult
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.SYSTEM_HOST_USER_ID
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.game.GameSessionRegistry
import com.dangerfield.cards.server.plugins.MATCHMAKING_FIND_LIMIT
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.SpanAttrs
import com.dangerfield.cards.server.plugins.userId
import com.dangerfield.cards.server.plugins.withSpan
import io.opentelemetry.api.trace.Span
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
 *  - `POST /v1/matchmaking/{code}/play-bots` — the searcher consented to the
 *    honest bot fallback ("we couldn't find enough players — play with bots").
 *    Fills the caller's public table with DISCLOSED bots (🤖, bot badge — never
 *    masked as human) up to a small target, then deals. Only the searcher's own
 *    forming public table; idempotent once the table is playing.
 *
 * `find` never seats a bot — the fallback is strictly opt-in via `play-bots`.
 * Cancelling a search is just the existing `DELETE /v1/rooms/{code}/me`.
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
 * A wallet check rejects a range whose top exceeds the caller's balance
 * (`insufficient_balance`, 400) — you can't search for a table you couldn't sit
 * at. This is the advisory affordability fence; the authoritative debit is the
 * sit-down escrow ([TableSessionService.sitDown], `mp_buyin`), which also applies
 * the stricter public-table entry bar. Find is intentionally lenient (1× balance).
 *
 * Still open, tracked so it's explicit, not forgotten:
 *  - **No global public-room cap.** The per-IP rate limit + the GC-on-leave that
 *    reclaims a solo searcher's table before they can stack up another are the
 *    only ceilings on concurrent public rooms. A dedicated global cap (plan
 *    §2.5) is unbuilt; add it if public-room count ever needs a hard bound.
 */
fun Route.matchmakingRoutes(
    rooms: RoomService,
    friends: FriendRepository,
    profiles: ProfileRepository,
    gameSessions: GameSessionRegistry,
    tableSessions: com.dangerfield.cards.server.domain.TableSessionService,
    equipmentRepository: EquipmentRepository,
    progressionRepository: ProgressionRepository,
    wallets: WalletRepository,
) {
    authenticate(SUPABASE_JWT_AUTH) {
        rateLimit(RateLimitName(MATCHMAKING_FIND_LIMIT)) {
            post("/v1/matchmaking/{code}/play-bots") {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val code = call.parameters["code"]?.uppercase()
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        matchmakingProblem("missing_code", "Room code is required."),
                    )
                val room = rooms.find(code)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        matchmakingProblem("room_not_found", "No room with code $code."),
                    )
                // Only the searcher's OWN public table, and only while it's still
                // forming — you can't conjure bots into someone else's game or a
                // table that's already dealt.
                if (room.visibility != RoomVisibility.Public) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        matchmakingProblem("not_public", "Bot fallback is only for public tables."),
                    )
                }
                if (room.memberFor(userId) == null) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        matchmakingProblem("not_a_member", "You're not seated at that table."),
                    )
                }
                // The bot fallback is for a LONE searcher. If a real player arrived
                // during the search (or while the player tapped), don't bot-fill —
                // the client should keep the real-human game. Keeps a bot table at
                // exactly one human, so its winnings are cleanly house-funded.
                if (room.members.count { !it.isBot } > 1) {
                    return@post call.respond(
                        HttpStatusCode.Conflict,
                        matchmakingProblem("real_player_present", "A real player joined — playing them instead."),
                    )
                }
                if (room.status != RoomStatus.Lobby) {
                    // Already dealt — idempotent success so a double-tap is benign.
                    return@post call.respond(
                        HttpStatusCode.OK,
                        MatchmakingFindResponse(room = room.toDto(), created = false),
                    )
                }

                // The honest disclosed-bot fallback consent — a budget-relevant
                // event. The span carries the table it landed on + the human/bot
                // split after the fill, so the dashboard can watch fallback rate.
                withSpan(
                    name = "matchmaking_play_bots",
                    configure = {
                        setAttribute(SpanAttrs.UserId, userId.value.toString())
                        setAttribute(SpanAttrs.RoomCode, code)
                    },
                ) {
                    // Fill DISCLOSED bots up to the lively-but-fast target, then deal.
                    // requestedBy is the synthetic system host (a public table's host),
                    // so the host-only gate passes. fillBotsUpTo is one atomic critical
                    // section, so a double-tap can't overshoot the target into a packed
                    // table — it's idempotent once the table is at/over target.
                    val filled = rooms.fillBotsUpTo(
                        code = code,
                        requestedBy = SYSTEM_HOST_USER_ID,
                        target = PUBLIC_BOT_FALLBACK_TARGET,
                        difficulty = BotDifficulty.Standard,
                        revealed = true,
                    )
                    val current = (filled as? com.dangerfield.cards.server.domain.AddBotResult.Success)?.room ?: room

                    startServerDealtTableIfReady(code, rooms, gameSessions, tableSessions, equipmentRepository, progressionRepository)

                    val dealt = rooms.find(code) ?: current
                    Span.current().apply {
                        setAttribute(SpanAttrs.MatchmakingHumans, dealt.members.count { !it.isBot }.toLong())
                        setAttribute(SpanAttrs.MatchmakingBots, dealt.members.count { it.isBot }.toLong())
                    }
                    call.respond(HttpStatusCode.OK, MatchmakingFindResponse(room = dealt.toDto(), created = false))
                }
            }

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
                // You can't search for a table you couldn't afford to sit at: the
                // top of the range is what you're willing to buy in for, so reject
                // it if it's more than your wallet holds. The client slider already
                // fences this — this is the authoritative backstop for a tampered or
                // stale request (e.g. balance dropped between screens).
                val balance = wallets.findOrCreate(userId).balance
                if (body.maxBuyIn > balance) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        matchmakingProblem(
                            "insufficient_balance",
                            "That buy-in is more than your balance of $balance chips.",
                        ),
                    )
                }

                val profile = profiles.findOrCreate(userId)
                // One indexed read, before the rooms mutex — never query the
                // friend graph under the lock.
                val blocked = friends.listBlockedUserIds(userId)

                // The core "is there organic density yet?" span: tier landed at,
                // created-vs-joined, real humans now seated, and (via the span's
                // own duration) find→seat latency. Sliceable in Tempo by tier.
                withSpan(
                    name = "matchmaking_find",
                    configure = { setAttribute(SpanAttrs.UserId, userId.value.toString()) },
                ) {
                    val result = rooms.findOrJoinPublic(
                        userId = userId,
                        name = profile.displayName,
                        minBuyIn = body.minBuyIn,
                        maxBuyIn = body.maxBuyIn,
                        blockedUserIds = blocked,
                        avatarEmoji = profile.avatarEmoji,
                        avatarBackgroundColor = profile.avatarBackgroundColor,
                    )
                    val created = result is MatchmakingResult.Created
                    Span.current().apply {
                        setAttribute(SpanAttrs.MatchmakingCreated, created)
                        setAttribute(SpanAttrs.MatchmakingBuyIn, result.room.buyIn)
                        setAttribute(SpanAttrs.MatchmakingHumans, result.room.members.count { !it.isBot }.toLong())
                    }
                    call.respond(
                        HttpStatusCode.OK,
                        MatchmakingFindResponse(room = result.room.toDto(), created = created),
                    )
                }
            }
        }
    }
}

private fun matchmakingProblem(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

/**
 * Seat target a bot-fallback public table fills to. Four is lively enough to
 * feel like a real table and deals fast — a packed six reads as suspicious and
 * plays slow. Real humans joining later trim the bots back down (Phase 3b).
 */
private const val PUBLIC_BOT_FALLBACK_TARGET = 4

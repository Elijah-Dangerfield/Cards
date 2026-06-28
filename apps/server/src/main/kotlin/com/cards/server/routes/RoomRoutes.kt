package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.AddBotResult
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.LeaveResult
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.RemoveBotResult
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * HTTP entry points for the multiplayer room lifecycle. Live state +
 * presence broadcasts go over the per-room WebSocket
 * (see `RoomSocketRoutes`); HTTP is for the one-shot operations that
 * need a reply (create, join, leave, fetch).
 *
 *  - `POST /v1/rooms`               — create. Body: `{ "maxSeats": 6? }`.
 *                                     Returns the new room + its code. The
 *                                     host is auto-seated; their socket
 *                                     connects next via /socket.
 *  - `GET  /v1/rooms/{code}`        — read current room state.
 *  - `POST /v1/rooms/{code}/join`   — join. Idempotent: re-joining
 *                                     returns the same seat.
 *  - `DELETE /v1/rooms/{code}/me`   — leave. Frees the seat; reaps the
 *                                     room if you were the last one.
 *                                     Idempotent: a room that's already
 *                                     gone or a membership already cleared
 *                                     (post-crash settlement, a re-issued
 *                                     leave) both resolve to 204 — the
 *                                     caller's goal of not being in the
 *                                     room is satisfied either way, so a
 *                                     404/409 there only reads as a dead
 *                                     leave button.
 *
 * Every route requires a Supabase JWT (userId comes from the `sub`
 * claim). Display name comes from the profile so members can't spoof
 * each other's names by tampering with the body.
 *
 * Status codes:
 *  - 200 — happy path
 *  - 404 — room code unknown / GC'd
 *  - 409 — room is full / no longer joinable / caller hosts too many rooms
 *  - 400 — malformed body (e.g. maxSeats out of range)
 *  - 401 — missing / invalid JWT (the auth plugin handles this for us)
 */
fun Route.roomRoutes(rooms: RoomService, profiles: ProfileRepository, wallets: WalletRepository) {
    authenticate(SUPABASE_JWT_AUTH) {
        route("/v1/rooms") {
            post {
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<CreateRoomRequest>()
                val maxSeats = body.maxSeats ?: RoomService.MAX_SEATS
                if (maxSeats !in MIN_SEATS..MAX_SEATS) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        problemEnvelope(
                            "invalid_max_seats",
                            "maxSeats must be between $MIN_SEATS and $MAX_SEATS.",
                        ),
                    )
                }
                val buyIn = body.buyIn ?: RoomSettings.DEFAULT_BUY_IN
                if (buyIn !in RoomSettings.MIN_BUY_IN..RoomSettings.MAX_BUY_IN) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        problemEnvelope(
                            "invalid_buy_in",
                            "buyIn must be between ${RoomSettings.MIN_BUY_IN} and ${RoomSettings.MAX_BUY_IN}.",
                        ),
                    )
                }
                // A client may open a room to matchmaking (Open) or keep it
                // code-only (Private, the default). It may NOT mint a Public table
                // — those come only from the matchmaker's find-or-create.
                val visibility = when (body.visibility) {
                    null, "Private" -> RoomVisibility.Private
                    "Open" -> RoomVisibility.Open
                    else -> return@post call.respond(
                        HttpStatusCode.BadRequest,
                        problemEnvelope(
                            "invalid_visibility",
                            "visibility must be \"Private\" or \"Open\".",
                        ),
                    )
                }
                // Host table cosmetics (SHOP-3) are opaque catalog ids; the server
                // never interprets them. Cap the length so a malformed body can't
                // store an unbounded string, and blank → null (no host override).
                val feltProductId = body.feltProductId.sanitizeProductId()
                val cardBackProductId = body.cardBackProductId.sanitizeProductId()
                val profile = profiles.findOrCreate(userId)
                when (val outcome = rooms.create(
                    hostUserId = userId,
                    hostName = profile.displayName,
                    maxSeats = maxSeats,
                    hostAvatarEmoji = profile.avatarEmoji,
                    hostAvatarBackgroundColor = profile.avatarBackgroundColor,
                    buyIn = buyIn,
                    visibility = visibility,
                    feltProductId = feltProductId,
                    cardBackProductId = cardBackProductId,
                )) {
                    is CreateResult.Success -> call.respond(
                        HttpStatusCode.OK,
                        CreateRoomResponse(room = outcome.room.toDto()),
                    )
                    is CreateResult.TooManyRooms -> call.respond(
                        HttpStatusCode.Conflict,
                        problemEnvelope(
                            "too_many_rooms",
                            "You already host ${outcome.activeCount} rooms. Leave one before creating another.",
                        ),
                    )
                }
            }

            get("/{code}") {
                val code = call.parameters["code"]?.uppercase()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val room = rooms.find(code)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        problemEnvelope("room_not_found", "No room with code $code."),
                    )
                call.respond(HttpStatusCode.OK, GetRoomResponse(room = room.toDto()))
            }

            post("/{code}/join") {
                val code = call.parameters["code"]?.uppercase()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                // Mirror the matchmaking affordability fence: you can't join a
                // table whose buy-in is more than your wallet holds. The private
                // join-by-code path had no such gate, so a tester joined a room
                // they couldn't sit at and was silently demoted to spectator by
                // escrow. Re-joins by an existing member skip the check — they
                // already sat down. An unknown code falls through to join()'s 404.
                val existing = rooms.find(code)
                if (existing != null && existing.memberFor(userId) == null) {
                    val balance = wallets.findOrCreate(userId).balance
                    if (existing.buyIn > balance) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            problemEnvelope(
                                "insufficient_balance",
                                "That table's buy-in of ${existing.buyIn} is more than your " +
                                    "balance of $balance chips.",
                            ),
                        )
                    }
                }
                val profile = profiles.findOrCreate(userId)
                when (
                    val outcome = rooms.join(
                        code = code,
                        userId = userId,
                        name = profile.displayName,
                        avatarEmoji = profile.avatarEmoji,
                        avatarBackgroundColor = profile.avatarBackgroundColor,
                    )
                ) {
                    is JoinResult.Success -> call.respond(
                        HttpStatusCode.OK,
                        JoinRoomResponse(room = outcome.room.toDto(), alreadyJoined = false),
                    )
                    is JoinResult.AlreadyJoined -> call.respond(
                        HttpStatusCode.OK,
                        JoinRoomResponse(room = outcome.room.toDto(), alreadyJoined = true),
                    )
                    JoinResult.RoomNotFound -> call.respond(
                        HttpStatusCode.NotFound,
                        problemEnvelope("room_not_found", "No room with code $code."),
                    )
                    JoinResult.Full -> call.respond(
                        HttpStatusCode.Conflict,
                        problemEnvelope("room_full", "That room is full."),
                    )
                    is JoinResult.NotJoinable -> call.respond(
                        HttpStatusCode.Conflict,
                        problemEnvelope(
                            "room_not_joinable",
                            "That room isn't accepting players right now (status: ${outcome.status}).",
                        ),
                    )
                }
            }

            delete("/{code}/me") {
                val code = call.parameters["code"]?.uppercase()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val userId = call.userId() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                // Leave is idempotent: room-gone and already-not-a-member
                // both mean the caller is no longer in the room, which is
                // exactly what they asked for. 204 across the board so a
                // re-issued or post-settlement leave never reads as a
                // failure (the dead back button in CARDS-2R / CARDS-34).
                when (rooms.leave(code, userId)) {
                    is LeaveResult.Success,
                    LeaveResult.RoomNotFound,
                    LeaveResult.NotInRoom,
                    -> call.respond(HttpStatusCode.NoContent)
                }
            }

            post("/{code}/bots") {
                val code = call.parameters["code"]?.uppercase()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId = call.userId() ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<AddBotRequest>()
                val difficulty = parseDifficulty(body.difficulty)
                    ?: return@post call.respond(
                        HttpStatusCode.BadRequest,
                        problemEnvelope(
                            "invalid_difficulty",
                            "difficulty must be one of Casual, Standard, Challenging.",
                        ),
                    )
                when (
                    val outcome = rooms.addBot(
                        code = code,
                        requestedBy = userId,
                        difficulty = difficulty,
                        seatIndex = body.seatIndex,
                    )
                ) {
                    is AddBotResult.Success -> call.respond(
                        HttpStatusCode.OK,
                        AddBotResponse(room = outcome.room.toDto()),
                    )
                    AddBotResult.RoomNotFound -> call.respond(
                        HttpStatusCode.NotFound,
                        problemEnvelope("room_not_found", "No room with code $code."),
                    )
                    AddBotResult.NotHost -> call.respond(
                        HttpStatusCode.Forbidden,
                        problemEnvelope("not_host", "Only the host can add bots."),
                    )
                    AddBotResult.Full -> call.respond(
                        HttpStatusCode.Conflict,
                        problemEnvelope("room_full", "That room is full."),
                    )
                    is AddBotResult.NotJoinable -> call.respond(
                        HttpStatusCode.Conflict,
                        problemEnvelope(
                            "room_not_joinable",
                            "You can only add bots while the room is in the lobby (status: ${outcome.status}).",
                        ),
                    )
                    AddBotResult.SeatTaken -> call.respond(
                        HttpStatusCode.Conflict,
                        problemEnvelope("seat_taken", "That seat isn't available."),
                    )
                }
            }

            delete("/{code}/bots/{botUserId}") {
                val code = call.parameters["code"]?.uppercase()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val userId = call.userId() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val botUserId = call.parameters["botUserId"]?.let(::parseUserIdOrNull)
                    ?: return@delete call.respond(
                        HttpStatusCode.NotFound,
                        problemEnvelope("bot_not_found", "No such bot in that room."),
                    )
                when (rooms.removeBot(code, userId, botUserId)) {
                    is RemoveBotResult.Success -> call.respond(HttpStatusCode.NoContent)
                    RemoveBotResult.RoomNotFound -> call.respond(
                        HttpStatusCode.NotFound,
                        problemEnvelope("room_not_found", "No room with code $code."),
                    )
                    RemoveBotResult.NotHost -> call.respond(
                        HttpStatusCode.Forbidden,
                        problemEnvelope("not_host", "Only the host can remove bots."),
                    )
                    RemoveBotResult.NotABot -> call.respond(
                        HttpStatusCode.NotFound,
                        problemEnvelope("bot_not_found", "No such bot in that room."),
                    )
                }
            }
        }
    }
}

/**
 * Normalise a host-supplied cosmetic product id (SHOP-3): trim, treat blank as
 * "no override", and drop anything implausibly long for a catalog id so a
 * malformed body can't stash an unbounded string on the room snapshot.
 */
private fun String?.sanitizeProductId(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_PRODUCT_ID_LENGTH }

/** Maps the optional wire difficulty name to the enum; null body → Standard. */
private fun parseDifficulty(raw: String?): BotDifficulty? {
    if (raw == null) return BotDifficulty.Standard
    return BotDifficulty.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}

private fun parseUserIdOrNull(raw: String): UserId? =
    try {
        UserId.parse(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

private const val MIN_SEATS = 2
private const val MAX_SEATS = 9
private const val MAX_PRODUCT_ID_LENGTH = 64

private fun problemEnvelope(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.game.GameSessionRegistry
import com.dangerfield.cards.server.game.IntentResult
import com.dangerfield.cards.server.game.SeatOccupant
import com.dangerfield.cards.server.plugins.SUPABASE_JWT_AUTH
import com.dangerfield.cards.server.plugins.userId
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.websocket.WebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import com.dangerfield.cards.libraries.gameplay.scrubbedFor

/**
 * `GET /v1/rooms/{code}/socket` (WebSocket upgrade) — the per-room
 * presence + multiplayer-gameplay channel.
 *
 * Auth: same Supabase JWT as HTTP routes (Ktor's `authenticate` block
 * works across the upgrade). Membership: the caller MUST already be a
 * member via POST /v1/rooms/{code}/join — we don't implicit-join here
 * because join is the spot where seat-allocation lives and we want
 * mutations to flow through one path.
 *
 * Connection lifecycle:
 *  1. Upgrade succeeds → markConnected(true) → broadcast Snapshot to
 *     this socket → enter the per-room flow subscription.
 *  2. Every room mutation (anyone joins/leaves/connects/disconnects)
 *     fans out to every subscriber as Snapshot + the matching delta
 *     event (MemberJoined / MemberLeft / MemberPresenceChanged).
 *  3. Socket dies (clean close OR ping timeout) → markConnected(false).
 *     Seat is held; other clients see the presence flip. The user can
 *     reopen another socket to resume — same userId, same seat.
 *
 * Reconnect grace: on disconnect we stamp the member with
 * `disconnectedAt = now` and schedule a per-member reaper on the
 * Application's coroutine scope — `delay(reaperGrace)` then
 * [RoomService.reapIfStillDisconnected] with the captured stamp. If the
 * user reconnects (stamp cleared) or re-drops (stamp refreshed) the
 * original reaper short-circuits to a no-op and the fresh disconnect
 * schedules its own timer. Same broadcast machinery handles the reap's
 * effect: the room flow re-emits and subscribers see a `member_left`
 * delta for the freed seat.
 *
 * Delta computation: we keep the previous snapshot per-subscription
 * and diff against the new one to emit the right delta events. The
 * Snapshot itself is always sent first so clients have a fallback
 * even if they miss a delta during flaky network.
 *
 * Game frames (added with server-authoritative multiplayer):
 *  - A second publisher subscribes to the [GameSessionRegistry] for
 *    this room. The moment the host's `StartHand` creates a session,
 *    every connected client begins receiving personalized
 *    [RoomSocketEventDto.GameStateSnapshot] frames (scrubbed via
 *    [scrubbedFor] using the viewer's own seat index) and a raw
 *    [RoomSocketEventDto.GameEventOccurred] stream for animations.
 *  - The drain loop now decodes client→server [RoomClientFrame]s
 *    (StartHand / SubmitIntent / RequestNextHand) and dispatches them
 *    into the registry. Each frame ships back an
 *    [RoomSocketEventDto.IntentAck] keyed by clientNonce so callers can
 *    correlate retries / surface rejection reasons.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun Route.roomSocketRoutes(
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
    reaperGrace: Duration = DEFAULT_REAPER_GRACE,
) {
    val app = application
    authenticate(SUPABASE_JWT_AUTH) {
        webSocket("/v1/rooms/{code}/socket") {
            val code = call.parameters["code"]?.uppercase()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "missing code"))
            val userId = call.userId()
                ?: return@webSocket close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))

            val current = rooms.find(code)
            if (current == null || current.memberFor(userId) == null) {
                // Not a member yet — must POST /join first. We don't
                // implicit-join here because the seat allocator lives in
                // join() and we want mutations to flow through one path.
                return@webSocket close(
                    CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "not a member of this room"),
                )
            }

            val flow = rooms.observe(code) ?: return@webSocket close(
                CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "room not found"),
            )

            rooms.markConnected(code, userId, connected = true)

            // The room-flow collector runs in a child coroutine so we
            // can independently watch [incoming] for the close signal.
            // Without this split, the collector blocks indefinitely on
            // a quiet room (no upstream emissions == no chance to
            // notice the socket died), and the finally block never
            // runs to flip isConnected back.
            val publisher = launch {
                try {
                    // scan() lets us diff the previous snapshot against
                    // the new one each tick. distinctUntilChanged
                    // suppresses idempotent re-emits.
                    flow.distinctUntilChanged()
                        .scan<Room, Pair<Room?, Room>?>(null) { acc, next ->
                            val previous = acc?.second
                            previous to next
                        }
                        .collect { pair ->
                            if (pair == null) return@collect
                            val (previous, next) = pair
                            if (previous == null) {
                                sendJson(RoomSocketEventDto.Snapshot(next.toDto()))
                            } else {
                                // Order: Snapshot first (always-correct
                                // state) then any deltas the client can
                                // use for toasts / animations.
                                sendJson(RoomSocketEventDto.Snapshot(next.toDto()))
                                diffDeltas(previous, next).forEach { sendJson(it) }
                            }
                        }
                } catch (_: CancellationException) {
                    // Expected when the outer loop cancels us on close.
                } catch (e: Throwable) {
                    LoggerFactory.getLogger("RoomSocket")
                        .warn("Socket for room=$code user=$userId died publishing", e)
                }
            }

            // Game-state publisher. Subscribes to the per-room
            // GameSessionRegistry stream; flatMapLatest swaps in the
            // session's flows the moment a session appears (typically
            // right after the host's StartHand) and resets if a session
            // is dropped + re-created. Each emit looks up the viewer's
            // seat from the live state by playerId match — robust if
            // the seat index ever shifts (V1 it doesn't, but cheap to
            // be correct).
            val userIdString = userId.value.toString()
            val gamePublisher = launch {
                try {
                    gameSessions.observeSession(code)
                        .flatMapLatest { session ->
                            if (session == null) emptyFlow<RoomSocketEventDto>()
                            else merge(
                                session.state
                                    .filterNotNull()
                                    .map { state ->
                                        val viewerSeat = state.seats
                                            .firstOrNull { it.playerId == userIdString }
                                            ?.index ?: -1
                                        RoomSocketEventDto.GameStateSnapshot(
                                            state.scrubbedFor(viewerSeat),
                                        )
                                    },
                                session.events.map {
                                    RoomSocketEventDto.GameEventOccurred(it)
                                },
                            )
                        }
                        .collect { sendJson(it) }
                } catch (_: CancellationException) {
                    // Expected on close.
                } catch (e: Throwable) {
                    LoggerFactory.getLogger("RoomSocket")
                        .warn("Game publisher for room=$code user=$userId died", e)
                }
            }

            try {
                // Drain incoming. We now decode client-bound game
                // frames (StartHand / SubmitIntent / RequestNextHand)
                // and dispatch into the registry. Unknown / malformed
                // frames are logged and dropped — same forward-compat
                // policy as the client's decode-and-drop on the
                // server-bound side.
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val raw = frame.readText()
                    val clientFrame = try {
                        JSON.decodeFromString(RoomClientFrame.serializer(), raw)
                    } catch (e: Throwable) {
                        LoggerFactory.getLogger("RoomSocket")
                            .warn("Bad client frame from room=$code user=$userId: ${e.message}")
                        continue
                    }
                    val ack = handleClientFrame(
                        code = code,
                        userId = userId,
                        frame = clientFrame,
                        rooms = rooms,
                        gameSessions = gameSessions,
                    )
                    sendJson(ack)
                }
            } catch (_: ClosedReceiveChannelException) {
                // Normal close path.
            } catch (_: CancellationException) {
                throw CancellationException("session cancelled")
            } catch (e: Throwable) {
                LoggerFactory.getLogger("RoomSocket")
                    .warn("Socket for room=$code user=$userId died reading", e)
            } finally {
                publisher.cancel()
                gamePublisher.cancel()
                // Mark disconnected regardless of close cause. Capture
                // the stamp the service just set so the reaper can
                // cross-check that the user didn't reconnect (or
                // re-drop) during the grace window.
                val afterDisconnect = rooms.markConnected(code, userId, connected = false)
                val droppedAt = afterDisconnect?.memberFor(userId)?.disconnectedAt
                if (droppedAt != null) {
                    app.launch {
                        try {
                            delay(reaperGrace)
                            rooms.reapIfStillDisconnected(code, userId, droppedAt)
                        } catch (_: CancellationException) {
                            // Server shutdown — leave the member; the
                            // next process boot won't have them anyway
                            // (rooms are in-memory).
                        } catch (e: Throwable) {
                            LoggerFactory.getLogger("RoomSocket")
                                .warn("Reaper for room=$code user=$userId failed", e)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Routes a single decoded [RoomClientFrame] into the right registry
 * call and packages the result into an [RoomSocketEventDto.IntentAck].
 * Pulled out of the route lambda so the dispatch table reads top-down.
 */
private suspend fun handleClientFrame(
    code: String,
    userId: UserId,
    frame: RoomClientFrame,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
): RoomSocketEventDto.IntentAck {
    val result: IntentResult = when (frame) {
        is RoomClientFrame.StartHand -> handleStartHand(code, userId, rooms, gameSessions)
        is RoomClientFrame.SubmitIntent -> gameSessions.applyIntent(
            code = code,
            actorUserId = userId.value.toString(),
            intent = frame.intent,
            clientNonce = frame.clientNonce,
        )
        is RoomClientFrame.RequestNextHand -> gameSessions.requestNextHand(
            code = code,
            actorUserId = userId.value.toString(),
            clientNonce = frame.clientNonce,
        )
    }
    return RoomSocketEventDto.IntentAck(
        clientNonce = frame.clientNonce,
        accepted = result is IntentResult.Accepted,
        error = (result as? IntentResult.Rejected)?.reason,
    )
}

/**
 * Host-gated start-hand handler. Pulls the room, validates host,
 * builds occupants from the current member list, calls the registry,
 * and (on success) flips the room status to Playing so the lobby
 * snapshot's `status` change cascades to all subscribers.
 */
private suspend fun handleStartHand(
    code: String,
    userId: UserId,
    rooms: RoomService,
    gameSessions: GameSessionRegistry,
): IntentResult {
    val room = rooms.find(code)
        ?: return IntentResult.Rejected("room not found")
    if (room.hostUserId != userId) {
        return IntentResult.Rejected("only the host can start the hand")
    }
    val occupants = room.members.map {
        SeatOccupant(
            seatIndex = it.seatIndex,
            userId = it.userId.value.toString(),
            displayName = it.displayName,
            isBot = false,
        )
    }
    if (occupants.size < 2) {
        return IntentResult.Rejected("need at least 2 players to start")
    }
    val result = gameSessions.startHand(code, occupants, RoomSettings.Default)
    if (result is IntentResult.Accepted) {
        rooms.markPlaying(code)
    }
    return result
}

/**
 * Default grace window before a disconnected member's seat is freed.
 * Short by design — V1 rooms are ephemeral and a blocked seat hurts
 * UX fast. Five minutes is enough for a typical reconnect on a flaky
 * cellular drop but short enough that an abandoned room is reusable
 * inside one hand of play.
 */
val DEFAULT_REAPER_GRACE: Duration = 5.minutes

private val JSON = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}

private suspend fun WebSocketServerSession.sendJson(event: RoomSocketEventDto) {
    send(Frame.Text(JSON.encodeToString(RoomSocketEventDto.serializer(), event)))
}

/**
 * Returns the delta events that turn [previous] into [next]. Order:
 * presence changes first (cheap, frequent), then leaves (drop them
 * before render), then joins (add at the end).
 *
 * Renames / seat-shuffles aren't reported as deltas — those are
 * implicitly captured by the Snapshot that goes out alongside.
 */
private fun diffDeltas(previous: Room, next: Room): List<RoomSocketEventDto> {
    val prevById = previous.members.associateBy { it.userId }
    val nextById = next.members.associateBy { it.userId }
    val out = mutableListOf<RoomSocketEventDto>()

    // Presence flips for members present in both snapshots.
    for ((id, nextMember) in nextById) {
        val prevMember = prevById[id] ?: continue
        if (prevMember.isConnected != nextMember.isConnected) {
            out += RoomSocketEventDto.MemberPresenceChanged(
                userId = id.value.toString(),
                isConnected = nextMember.isConnected,
            )
        }
    }
    // Leaves: anyone in previous but not next.
    for ((id, _) in prevById) {
        if (id !in nextById) {
            out += RoomSocketEventDto.MemberLeft(userId = id.value.toString())
        }
    }
    // Joins: anyone in next but not previous.
    for ((id, member) in nextById) {
        if (id !in prevById) {
            out += RoomSocketEventDto.MemberJoined(member = member.toDto())
        }
    }
    return out
}

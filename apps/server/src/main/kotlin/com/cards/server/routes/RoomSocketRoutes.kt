package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomService
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * `GET /v1/rooms/{code}/socket` (WebSocket upgrade) — the per-room
 * presence + future-gameplay channel.
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
 */
fun Route.roomSocketRoutes(
    rooms: RoomService,
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

            try {
                // Drain incoming until the client closes. V1 has no
                // client→server message types; we just need to know
                // when the channel terminates. The for-loop exits
                // when the channel closes for any reason (ping timeout,
                // client close, error).
                for (frame in incoming) {
                    // No client messages today — discard.
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


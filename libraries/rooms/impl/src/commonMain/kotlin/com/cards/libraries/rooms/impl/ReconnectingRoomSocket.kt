package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.isSecure
import io.ktor.http.path
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.math.min
import kotlin.math.pow

/**
 * Opens (and reopens) a per-room WebSocket, translating server frames
 * into [RoomConnection] state transitions.
 *
 * Reconnect policy: exponential backoff starting at 250ms, doubling per
 * attempt, capped at 16s. Stops reconnecting on two terminal signals:
 *  - The server sends `room_closed` (no point reconnecting to a dead
 *    room).
 *  - The server rejects the handshake (4xx / Close immediately after
 *    upgrade) — usually means the user isn't a member of the room.
 *    The collector should call POST /join + re-subscribe.
 *
 * Forward-compat: unrecognized event types throw at JSON decode time
 * and the loop drops them with a warning. The Snapshot baseline keeps
 * the client correct even when a delta variant is unknown.
 *
 * Cancellation: cancelling the flow collector closes the underlying
 * session via [channelFlow]'s `awaitClose`.
 */
interface RoomSocket {
    fun observe(code: String): Flow<RoomConnection>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ReconnectingRoomSocket(
    private val networkClient: NetworkClient,
) : RoomSocket {

    private val logger = KLog.withTag("RoomSocket")

    override fun observe(code: String): Flow<RoomConnection> = channelFlow {
        send(RoomConnection.Connecting)

        var attempt = 0
        var lastRoom: Room? = null
        var stop = false

        while (!stop) {
            val session = try {
                networkClient.authenticatedClient.webSocketSession {
                    socketRequest(code)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Handshake failure — almost always transient (offline,
                // server hiccup). Surface Reconnecting + back off.
                attempt += 1
                logger.w(e) { "Room socket handshake failed (attempt $attempt)" }
                send(RoomConnection.Reconnecting(attempt, e))
                delay(backoffFor(attempt))
                continue
            }

            // Handshake succeeded — reset the attempt counter so the
            // next failure backs off from scratch instead of compounding.
            attempt = 0

            try {
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    val event = decode(frame) ?: continue
                    when (event) {
                        is RoomSocketEventDto.Snapshot -> {
                            val room = event.room.toDomain()
                            lastRoom = room
                            send(RoomConnection.Connected(room))
                        }
                        // Deltas don't carry the full state — clients
                        // can use them for toasts/animations, but our
                        // state-of-the-world is always the latest
                        // Snapshot. We don't synthesize a Connection
                        // event on deltas.
                        is RoomSocketEventDto.MemberJoined,
                        is RoomSocketEventDto.MemberLeft,
                        is RoomSocketEventDto.MemberPresenceChanged,
                            -> Unit
                        RoomSocketEventDto.RoomClosed -> {
                            // Terminal — no point reconnecting.
                            send(RoomConnection.Closed(ClosedReason.RoomDeleted))
                            stop = true
                            return@channelFlow
                        }
                    }
                }
                // Channel closed cleanly without RoomClosed — server
                // dropped us (deploy / restart / network). Try to
                // reconnect with backoff.
                attempt += 1
                send(RoomConnection.Reconnecting(attempt, null))
                delay(backoffFor(attempt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                attempt += 1
                logger.w(e) { "Room socket dropped mid-stream (attempt $attempt)" }
                send(RoomConnection.Reconnecting(attempt, e))
                delay(backoffFor(attempt))
            } finally {
                Catching {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "client-closing"))
                }
            }
        }
    }

    private fun HttpRequestBuilder.socketRequest(code: String) {
        // Auth bearer rides on the authenticated client's Auth plugin —
        // no need to set Authorization here. The base URL (config-driven)
        // is HTTP; the URLProtocol upgrade flips ws/wss based on whether
        // the base is http/https.
        url {
            // Promote the base scheme to its WebSocket equivalent.
            // ContentNegotiation isn't part of WS handshake so we don't
            // touch headers further.
            val httpsBase = protocol.isSecure()
            protocol = if (httpsBase) URLProtocol.WSS else URLProtocol.WS
            path("v1", "rooms", code.uppercase(), "socket")
        }
        method = HttpMethod.Get
    }

    private fun decode(frame: Frame.Text): RoomSocketEventDto? = try {
        RoomSocketJson.decodeFromString(RoomSocketEventDto.serializer(), frame.readText())
    } catch (e: Throwable) {
        logger.w(e) { "Dropped unrecognized socket frame" }
        null
    }

    /**
     * Exponential backoff with cap. attempt=1 → 250ms,
     * 2 → 500ms, 3 → 1s, 4 → 2s, … cap at 16s. Multiply by [0.5, 1.5]
     * jitter so a thundering-herd reconnect after a server restart
     * spreads out.
     */
    private fun backoffFor(attempt: Int): Long {
        val base = (BACKOFF_BASE_MS * 2.0.pow(attempt - 1)).toLong()
        val capped = min(base, BACKOFF_CAP_MS)
        val jitterMul = 0.5 + kotlin.random.Random.nextDouble(1.0) // 0.5..1.5
        return (capped * jitterMul).toLong()
    }

    companion object {
        private const val BACKOFF_BASE_MS: Long = 250
        private const val BACKOFF_CAP_MS: Long = 16_000
    }
}

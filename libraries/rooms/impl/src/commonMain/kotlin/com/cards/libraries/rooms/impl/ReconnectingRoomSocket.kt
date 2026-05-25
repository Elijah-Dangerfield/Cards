package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.NetworkConfig
import com.dangerfield.cards.libraries.networking.authedWebSocketSession
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpMethod
import io.ktor.http.URLProtocol
import io.ktor.http.Url
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
 *  - The server returns a 4xx on the handshake — usually means the user
 *    isn't a member of the room. Surfaces as [ClosedReason.Rejected];
 *    the collector should call POST /join + re-subscribe. 5xx and
 *    transport-level failures still surface as [RoomConnection.Reconnecting]
 *    and back off — they're the server's transient problem, not ours.
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
    private val networkConfig: NetworkConfig,
) : RoomSocket {

    private val logger = KLog.withTag("RoomSocket")

    override fun observe(code: String): Flow<RoomConnection> = channelFlow {
        send(RoomConnection.Connecting)

        var attempt = 0
        var lastRoom: Room? = null
        var stop = false

        while (!stop) {
            val session = try {
                networkClient.authedWebSocketSession("rooms.socket") {
                    socketRequest(code)
                }.getOrThrow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ClientRequestException) {
                val status = e.response.status
                logger.w(e) { "Room socket rejected during handshake: ${status.value} ${status.description}" }
                send(RoomConnection.Closed(ClosedReason.Rejected))
                return@channelFlow
            } catch (e: WebSocketException) {
                val status = e.handshakeStatusOrNull()
                if (status != null && status in 400..499) {
                    logger.w(e) { "Room socket rejected during handshake: status $status" }
                    send(RoomConnection.Closed(ClosedReason.Rejected))
                    return@channelFlow
                }
                attempt += 1
                val suffix = status?.let { " (status $it)" }.orEmpty()
                logger.w(e) { "Room socket handshake failed$suffix (attempt $attempt)" }
                send(RoomConnection.Reconnecting(attempt, e))
                delay(backoffFor(attempt))
                continue
            } catch (e: Throwable) {
                attempt += 1
                val statusSuffix = (e as? ResponseException)?.response?.status?.value
                    ?.let { " (status $it)" }
                    .orEmpty()
                logger.w(e) { "Room socket handshake failed$statusSuffix (attempt $attempt)" }
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
                        // Multiplayer gameplay frames flow on a sibling
                        // channel (Phase 2b) so PlayPoker can subscribe
                        // without touching lobby concerns. The lobby
                        // observer in this when ignores them.
                        is RoomSocketEventDto.GameStateSnapshot,
                        is RoomSocketEventDto.GameEventOccurred,
                        is RoomSocketEventDto.IntentAck,
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
        // no need to set Authorization here.
        //
        // We set protocol/host/port explicitly from the parsed base URL
        // instead of leaning on DefaultRequest's merge. Two reasons:
        //  1. The Ktor WebSockets plugin pre-fills the URLBuilder with
        //     `protocol = WS, port = 80` before this block runs. If we
        //     only flip the protocol to WSS, port 80 sticks and Ktor
        //     attempts TLS against the Fly server's plaintext :80 — the
        //     handshake fails with `WRONG_VERSION_NUMBER`.
        //  2. DefaultRequest's URL merge copies host from the base URL
        //     only when the request host is empty, and it leaves any
        //     already-set port alone. So we have to populate both
        //     ourselves to override the WS-plugin defaults.
        val base = Url(networkConfig.baseUrl)
        val useWss = base.protocol.name.equals("https", ignoreCase = true)
        url {
            protocol = if (useWss) URLProtocol.WSS else URLProtocol.WS
            host = base.host
            port = base.port
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

/**
 * Ktor's [WebSocketException] embeds the HTTP status in its message
 * ("Handshake exception, expected status code 101 but was 403") with
 * no structured accessor. Parsing the message is the only way to
 * classify the upgrade failure into 4xx (terminal — server rejected
 * membership) vs 5xx (transient — retry). The format is stable across
 * Ktor 2.x/3.x; unparseable messages fall back to the retry path.
 */
private fun WebSocketException.handshakeStatusOrNull(): Int? =
    message?.let { HandshakeStatusPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }

private val HandshakeStatusPattern = Regex("expected status code 101 but was (\\d{3})")

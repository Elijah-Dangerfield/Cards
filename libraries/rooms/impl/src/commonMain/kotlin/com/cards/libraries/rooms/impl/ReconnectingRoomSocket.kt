package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.NetworkConfig
import com.dangerfield.cards.libraries.networking.authedWebSocketSession
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.math.min
import kotlin.math.pow

/**
 * Opens (and reopens) a per-room WebSocket and exposes it through a
 * [RoomConnectionHandle] that splits the stream into a lobby-shaped
 * [RoomConnection] flow, a raw gameplay-frame flow, and an outbound
 * write channel — all backed by one underlying socket.
 *
 * Sharing model: handles for the same room code reuse one
 * [SharedSocketState], cached on this singleton. A per-state
 * coordinator coroutine watches the two `SharedFlow.subscriptionCount`
 * sums; while at least one collector exists the WS runs, otherwise it
 * shuts down. Navigating between lobby and gameplay screens does not
 * tear the socket down because both screens collect through the same
 * shared state.
 *
 * Reconnect policy: exponential backoff starting at 250ms, doubling
 * per attempt, capped at 16s. Stops reconnecting on two terminal
 * signals:
 *  - The server sends `room_closed` (no point reconnecting to a dead
 *    room).
 *  - The server returns a 4xx on the handshake — usually means the
 *    user isn't a member of the room. Surfaces as
 *    [ClosedReason.Rejected]; the collector should call POST /join +
 *    re-subscribe. 5xx and transport-level failures still surface as
 *    [RoomConnection.Reconnecting] and back off — they're the server's
 *    transient problem, not ours.
 *
 * Forward-compat: unrecognized event types throw at JSON decode time
 * and the loop drops them with a warning.
 */
interface RoomSocket {
    fun connect(code: String): RoomConnectionHandle
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ReconnectingRoomSocket(
    private val networkClient: NetworkClient,
    private val networkConfig: NetworkConfig,
    private val appScope: AppCoroutineScope,
) : RoomSocket {

    private val logger = KLog.withTag("RoomSocket")

    private val statesMutex = Mutex()
    private val statesByCode = mutableMapOf<String, SharedSocketState>()

    override fun connect(code: String): RoomConnectionHandle = HandleImpl(code)

    private suspend fun stateFor(code: String): SharedSocketState = statesMutex.withLock {
        statesByCode.getOrPut(code) { SharedSocketState(code) }
    }

    private inner class HandleImpl(private val code: String) : RoomConnectionHandle {
        override val connection: Flow<RoomConnection> = flow {
            emitAll(stateFor(code).connection)
        }

        override val gameplayFrames: Flow<GameplayFrame> = flow {
            emitAll(stateFor(code).gameplayFrames)
        }

        override suspend fun send(frame: ClientFrame) {
            stateFor(code).send(frame)
        }
    }

    /**
     * Per-room shared state. Lifecycle is driven by the two
     * [SharedFlow.subscriptionCount]s: while their sum is > 0 the WS
     * runs, otherwise the coordinator parks. Outbound frames buffer in
     * a Channel and drain to the live session when one exists; on
     * reconnect any buffered frames re-send (server-side nonce dedupe
     * handles the duplicate-after-reconnect case).
     */
    private inner class SharedSocketState(val code: String) {
        private val outbound = Channel<ClientFrame>(Channel.UNLIMITED)

        private val _connection = MutableSharedFlow<RoomConnection>(
            replay = 1,
            extraBufferCapacity = 8,
        )
        val connection: SharedFlow<RoomConnection> = _connection.asSharedFlow()

        private val _gameplayFrames = MutableSharedFlow<GameplayFrame>(
            replay = 0,
            extraBufferCapacity = 64,
        )
        val gameplayFrames: SharedFlow<GameplayFrame> = _gameplayFrames.asSharedFlow()

        init {
            appScope.launch {
                combine(
                    _connection.subscriptionCount,
                    _gameplayFrames.subscriptionCount,
                ) { a, b -> (a + b) > 0 }
                    .distinctUntilChanged()
                    .collectLatest { hasSubscribers ->
                        if (hasSubscribers) runSocketLoop()
                    }
            }
        }

        suspend fun send(frame: ClientFrame) {
            outbound.send(frame)
        }

        private suspend fun runSocketLoop() {
            _connection.emit(RoomConnection.Connecting)

            var attempt = 0
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
                    _connection.emit(RoomConnection.Closed(ClosedReason.Rejected))
                    return
                } catch (e: WebSocketException) {
                    val status = e.handshakeStatusOrNull()
                    if (status != null && status in 400..499) {
                        logger.w(e) { "Room socket rejected during handshake: status $status" }
                        _connection.emit(RoomConnection.Closed(ClosedReason.Rejected))
                        return
                    }
                    attempt += 1
                    val suffix = status?.let { " (status $it)" }.orEmpty()
                    logger.w(e) { "Room socket handshake failed$suffix (attempt $attempt)" }
                    _connection.emit(RoomConnection.Reconnecting(attempt, e))
                    delay(backoffFor(attempt))
                    continue
                } catch (e: Throwable) {
                    attempt += 1
                    val statusSuffix = (e as? ResponseException)?.response?.status?.value
                        ?.let { " (status $it)" }
                        .orEmpty()
                    logger.w(e) { "Room socket handshake failed$statusSuffix (attempt $attempt)" }
                    _connection.emit(RoomConnection.Reconnecting(attempt, e))
                    delay(backoffFor(attempt))
                    continue
                }

                attempt = 0

                // Writer side: drain outbound channel and ship frames to
                // the live session. Lives only for this connection — a
                // reconnect cancels the writer and a fresh one starts
                // with the next loop iteration, but the outbound channel
                // itself survives, so frames queued during the gap
                // re-send on reconnect.
                val writerJob: Job = appScope.launch {
                    try {
                        for (frame in outbound) {
                            val text = RoomSocketJson.encodeToString(
                                ClientFrame.serializer(),
                                frame,
                            )
                            session.send(Frame.Text(text))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.w(e) { "Outbound frame send failed" }
                    }
                }

                try {
                    for (frame in session.incoming) {
                        if (frame !is Frame.Text) continue
                        val event = decode(frame) ?: continue
                        when (event) {
                            is RoomSocketEventDto.Snapshot -> {
                                val room: Room = event.room.toDomain()
                                _connection.emit(RoomConnection.Connected(room))
                            }
                            // Lobby-side deltas don't carry full state —
                            // the Snapshot baseline already keeps clients
                            // correct, so we drop these. A future cycle
                            // can surface them as animation hints.
                            is RoomSocketEventDto.MemberJoined,
                            is RoomSocketEventDto.MemberLeft,
                            is RoomSocketEventDto.MemberPresenceChanged,
                                -> Unit
                            is RoomSocketEventDto.GameStateSnapshot ->
                                _gameplayFrames.emit(GameplayFrame.StateSnapshot(event.state))
                            is RoomSocketEventDto.GameEventOccurred ->
                                _gameplayFrames.emit(GameplayFrame.Event(event.seq, event.event))
                            is RoomSocketEventDto.IntentAck ->
                                _gameplayFrames.emit(
                                    GameplayFrame.IntentAck(
                                        clientNonce = event.clientNonce,
                                        accepted = event.accepted,
                                        error = event.error,
                                    ),
                                )
                            RoomSocketEventDto.RoomClosed -> {
                                _connection.emit(RoomConnection.Closed(ClosedReason.RoomDeleted))
                                stop = true
                                writerJob.cancel()
                                return
                            }
                        }
                    }
                    attempt += 1
                    _connection.emit(RoomConnection.Reconnecting(attempt, null))
                    delay(backoffFor(attempt))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    attempt += 1
                    logger.w(e) { "Room socket dropped mid-stream (attempt $attempt)" }
                    _connection.emit(RoomConnection.Reconnecting(attempt, e))
                    delay(backoffFor(attempt))
                } finally {
                    writerJob.cancel()
                    Catching {
                        session.close(CloseReason(CloseReason.Codes.NORMAL, "client-closing"))
                    }
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

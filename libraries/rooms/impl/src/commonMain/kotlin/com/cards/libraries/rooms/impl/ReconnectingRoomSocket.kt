package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.WebSocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
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
 * [SharedSocketState] cached on this singleton. A per-state
 * coordinator coroutine watches the two `SharedFlow.subscriptionCount`
 * sums; while at least one collector exists the WS runs, otherwise it
 * waits [STOP_LINGER_MS] and shuts down. The linger means navigating
 * lobby → play screen doesn't tear the socket down: the lobby's
 * collector cancels, the gameplay collector mounts a frame later, and
 * the WS stays open across the gap. After a full linger with no
 * collectors the state evicts itself from the cache so we don't leak
 * one coordinator + two SharedFlows per code the user ever visited.
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
class ReconnectingRoomSocket @Inject constructor(
    private val transport: RoomSocketTransport,
    private val appScope: AppCoroutineScope,
) : RoomSocket {

    private val logger = KLog.withTag("RoomSocket")

    private val statesMutex = Mutex()
    private val statesByCode = mutableMapOf<String, SharedSocketState>()

    override fun connect(code: String): RoomConnectionHandle = HandleImpl(code)

    private suspend fun stateFor(code: String): SharedSocketState = statesMutex.withLock {
        statesByCode.getOrPut(code) { SharedSocketState(code) }
    }

    /**
     * Try to evict [state] from the cache. Returns false if the entry
     * has already been replaced by a newer state (race between linger
     * expiry and a fresh `connect()`).
     *
     * Implemented as a manual identity-check-then-remove rather than
     * `Map.remove(key, value)`: the two-arg `remove` is a JVM-only
     * extension and breaks iOS compilation. The atomicity the two-arg
     * form guarantees is already ours from being inside the mutex.
     */
    private suspend fun evict(code: String, state: SharedSocketState): Boolean =
        statesMutex.withLock {
            if (statesByCode[code] === state) {
                statesByCode.remove(code)
                true
            } else {
                false
            }
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
     * Per-room shared state. Owns the two outbound SharedFlows, the
     * outbound queue, and the coordinator coroutine that runs the WS
     * loop while at least one collector exists.
     */
    private inner class SharedSocketState(val code: String) {

        /**
         * SUSPEND overflow with a small bound: if the WS has been
         * disconnected long enough that 32 frames pile up, the caller
         * suspends rather than silently buffering an unbounded queue.
         * On reconnect the writer drains in FIFO order; server-side
         * nonce dedupe collapses any frames the server already saw
         * before the drop. Stale per-turn intents are a separate
         * problem captured in `docs/backlog.md`.
         */
        private val outbound = Channel<ClientFrame>(
            capacity = OUTBOUND_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

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

        private val coordinatorJob: Job = appScope.launch { runCoordinator() }

        suspend fun send(frame: ClientFrame) {
            outbound.send(frame)
        }

        /**
         * Lifecycle: the WS loop runs while any subscriber is active,
         * parks after [STOP_LINGER_MS] of zero subscribers, and evicts
         * the state from the cache once the linger fully elapses.
         *
         * Why `combine + distinctUntilChanged + a per-edge launch`:
         *  - combine + map { sum > 0 } collapses both flows to a single
         *    "has anyone subscribed?" boolean.
         *  - distinctUntilChanged silences subscriber-count deltas that
         *    don't cross the 0 boundary (a second collector joining
         *    must NOT restart the WS).
         *  - A manual loopJob / pendingStopJob pair handles the
         *    linger: on every edge we cancel whatever stop-timer is
         *    pending, and the WS only actually closes after the timer
         *    elapses. This is hairier than the simpler
         *    `collectLatest { if (hasSubs) runWsLoop() }` we had
         *    before, but that version closed the WS instantly on the
         *    last unsubscribe — defeating the cross-screen sharing.
         */
        private suspend fun runCoordinator() = coroutineScope {
            var loopJob: Job? = null
            var pendingStopJob: Job? = null

            try {
                combine(
                    _connection.subscriptionCount,
                    _gameplayFrames.subscriptionCount,
                ) { a, b -> (a + b) > 0 }
                    .distinctUntilChanged()
                    .collect { hasSubscribers ->
                        if (hasSubscribers) {
                            pendingStopJob?.cancel()
                            pendingStopJob = null
                            if (loopJob?.isActive != true) {
                                loopJob = launch { runSocketLoop() }
                            }
                        } else {
                            pendingStopJob?.cancel()
                            pendingStopJob = launch {
                                delay(STOP_LINGER_MS)
                                loopJob?.cancel()
                                loopJob = null
                                if (evict(code, this@SharedSocketState)) {
                                    // Stop the coordinator from the
                                    // inside — once we've evicted, a
                                    // future connect() builds a fresh
                                    // state.
                                    coordinatorJob.cancel()
                                }
                            }
                        }
                    }
            } finally {
                loopJob?.cancel()
                pendingStopJob?.cancel()
            }
        }

        /**
         * The actual read/write loop. Runs until externally cancelled
         * (subscribers all gone past linger), receives a terminal
         * frame (RoomClosed), or hits a terminal handshake (4xx). On
         * any of those it returns normally and the coordinator parks.
         *
         * `coroutineScope { ... }` parents the writer to this loop, so
         * external cancellation cascades and the writer never outlives
         * the connection it was meant to drain.
         */
        private suspend fun runSocketLoop() = coroutineScope {
            // The replay buffer's purpose is "a fresh collector sees
            // the current state". A leftover `Reconnecting(attempt=5)`
            // from a prior session of the same code is not the current
            // state — clear it so the new collector sees a clean
            // Connecting → … sequence.
            _connection.resetReplayCache()
            _connection.emit(RoomConnection.Connecting)

            var attempt = 0

            while (true) {
                val sessionResult = transport.open(code)
                val session = sessionResult.getOrElse { e ->
                    when (val classification = classifyHandshake(e)) {
                        Handshake.Terminal -> {
                            logger.w(e) { "Room socket rejected during handshake (terminal)" }
                            _connection.emit(RoomConnection.Closed(ClosedReason.Rejected))
                            return@coroutineScope
                        }
                        is Handshake.Retry -> {
                            attempt += 1
                            logger.w(e) { "Room socket handshake failed${classification.suffix} (attempt $attempt)" }
                            _connection.emit(RoomConnection.Reconnecting(attempt, e))
                            delay(backoffFor(attempt))
                            continue
                        }
                    }
                }

                attempt = 0
                val terminal = runConnectedSession(session)
                if (terminal) return@coroutineScope

                // Clean drop without RoomClosed — server dropped us
                // (deploy / restart / brief network glitch). Retry.
                attempt += 1
                _connection.emit(RoomConnection.Reconnecting(attempt, null))
                delay(backoffFor(attempt))
            }
        }

        /**
         * Drives one live session until it drops or completes a
         * terminal frame. Returns true on terminal (no reconnect),
         * false on clean drop (reconnect with backoff). Owns its own
         * `coroutineScope` so the writer is structurally a child of
         * this session and dies with it.
         */
        private suspend fun runConnectedSession(session: RoomSocketSession): Boolean =
            coroutineScope {
                val writerJob = launch { drainOutbound(session) }
                var terminal = false
                try {
                    session.incoming.collect { text ->
                        val event = decode(text) ?: return@collect
                        when (event) {
                            is RoomSocketEventDto.Snapshot -> {
                                val room: Room = event.room.toDomain()
                                _connection.emit(RoomConnection.Connected(room))
                            }
                            // Lobby-side deltas don't carry full state;
                            // the Snapshot baseline keeps clients
                            // correct. A future cycle can surface them
                            // as animation hints.
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
                                terminal = true
                                // Use a private cancellation marker to
                                // break out of `collect` — there's no
                                // natural completion signal on the
                                // incoming flow until the server
                                // actually closes the WS, which may
                                // race the next frame.
                                throw TerminalFrameMarker
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // Distinguish our control-flow marker from an
                    // external cancellation (subscriber drop / app
                    // shutdown). Marker → propagate `terminal = true`
                    // through; real cancellation → propagate up.
                    if (e !== TerminalFrameMarker) throw e
                } catch (e: Throwable) {
                    logger.w(e) { "Room socket dropped mid-stream" }
                } finally {
                    writerJob.cancel()
                    session.close()
                }
                terminal
            }

        private suspend fun CoroutineScope.drainOutbound(session: RoomSocketSession) {
            try {
                for (frame in outbound) {
                    val text = RoomSocketJson.encodeToString(ClientFrame.serializer(), frame)
                    session.send(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.w(e) { "Outbound frame send failed" }
            }
        }
    }

    private fun decode(text: String): RoomSocketEventDto? = try {
        RoomSocketJson.decodeFromString(RoomSocketEventDto.serializer(), text)
    } catch (e: Throwable) {
        logger.w(e) { "Dropped unrecognized socket frame" }
        null
    }

    /**
     * Classifies a handshake failure as terminal (4xx — caller must
     * re-join) or retryable (5xx, transport, unknown).
     */
    private fun classifyHandshake(e: Throwable): Handshake = when (e) {
        is ClientRequestException -> Handshake.Terminal
        is WebSocketException -> {
            val status = e.handshakeStatusOrNull()
            if (status != null && status in 400..499) Handshake.Terminal
            else Handshake.Retry(status?.let { " (status $it)" }.orEmpty())
        }
        else -> {
            val status = (e as? ResponseException)?.response?.status?.value
            Handshake.Retry(status?.let { " (status $it)" }.orEmpty())
        }
    }

    private sealed interface Handshake {
        data object Terminal : Handshake
        data class Retry(val suffix: String) : Handshake
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

        /** How long to keep the WS open after the last subscriber unsubscribes. */
        internal const val STOP_LINGER_MS: Long = 1_000

        /** Outbound buffer cap; a full buffer suspends `send()`. */
        private const val OUTBOUND_CAPACITY: Int = 32
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

/**
 * Private control-flow marker thrown to break out of the
 * `incoming.collect` body once a terminal frame (RoomClosed) lands.
 * Distinguished from real cancellation by identity check.
 */
private object TerminalFrameMarker : CancellationException("room-closed")

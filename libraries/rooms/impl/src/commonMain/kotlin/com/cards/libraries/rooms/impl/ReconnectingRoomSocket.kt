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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
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
 * per attempt, capped at 16s. Stops reconnecting on three terminal
 * signals:
 *  - The server sends `room_closed` (no point reconnecting to a dead
 *    room).
 *  - The server returns a 4xx on the handshake — usually means the
 *    user isn't a member of the room. Surfaces as
 *    [ClosedReason.Rejected]; the collector should call POST /join +
 *    re-subscribe. 5xx and transport-level failures still surface as
 *    [RoomConnection.Reconnecting] and back off — they're the server's
 *    transient problem, not ours.
 *  - [MAX_RECONNECT_ATTEMPTS] consecutive failures without the socket
 *    ever becoming healthy. A half-open server socket (e.g. left behind
 *    after the sole other human leaves) accepts the WS handshake and
 *    then drops it instantly, delivering no frames — the old loop reset
 *    the attempt counter on every handshake success, so it spun
 *    connect→drop→reconnect forever at the 250ms floor. The counter now
 *    only resets once a session is *healthy* (delivers at least one
 *    frame), so an instantly-dropping socket keeps climbing the backoff
 *    and lands on [ClosedReason.ReconnectFailed] instead of looping.
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

        /**
         * Latest game-state snapshot, retained with replay-1 semantics so a
         * collector that subscribes *after* the deal — e.g. the non-host
         * navigating to the play screen on the host's `StartHand` — still
         * gets the current table instead of an empty one. Mirrors the
         * server's `GameSession.state` StateFlow. Conflated: only the latest
         * snapshot matters, and the client's `isStale()` guards intra-hand
         * ordering if a delayed frame lands after a newer one. NOT reset on
         * reconnect — keeping the last table visible while reconnecting is
         * the desired UX, and the server re-sends a fresh snapshot on
         * resubscribe.
         */
        private val _latestGameState = MutableStateFlow<GameplayFrame.StateSnapshot?>(null)

        /**
         * Live event + ack stream. `replay = 0` deliberately: a mid-hand
         * joiner renders the correct table from [_latestGameState] and must
         * NOT have the opening deal animation re-fire from a replayed event
         * burst.
         */
        private val _gameplayFrames = MutableSharedFlow<GameplayFrame>(
            replay = 0,
            extraBufferCapacity = 64,
        )

        /**
         * The retained latest snapshot merged with the live event/ack
         * stream. A fresh collector immediately receives the current game
         * state, then live frames. Snapshots flow only through
         * [_latestGameState]; events/acks only through [_gameplayFrames] —
         * no double-delivery. The coordinator still tracks subscribers via
         * [_gameplayFrames] (every collector of this merge subscribes to it).
         */
        val gameplayFrames: Flow<GameplayFrame> =
            merge(_latestGameState.filterNotNull(), _gameplayFrames)

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

                // Info: handshake succeeded — pairs with the close/drop logs to
                // bound a connected window in a reported session's breadcrumbs.
                logger.i { "Room socket connected (code=$code)" }
                val outcome = runConnectedSession(session)
                if (outcome.terminal) return@coroutineScope

                // A session that delivered at least one frame was genuinely
                // healthy — reset the counter so a single mid-game drop after
                // a long, working connection starts its backoff from scratch.
                // A session that dropped without ever delivering a frame is the
                // half-open-socket signature: do NOT reset, so repeated instant
                // drops climb toward the ceiling instead of looping forever.
                if (outcome.deliveredFrame) attempt = 0

                // Clean drop without RoomClosed — server dropped us
                // (deploy / restart / brief network glitch, or a half-open
                // peer socket). Retry until the ceiling.
                attempt += 1
                if (attempt > MAX_RECONNECT_ATTEMPTS) {
                    logger.w {
                        "Room socket giving up after $MAX_RECONNECT_ATTEMPTS reconnects (code=$code)"
                    }
                    _connection.emit(RoomConnection.Closed(ClosedReason.ReconnectFailed))
                    return@coroutineScope
                }
                val backoff = backoffFor(attempt)
                // Info: a routine reconnect (vs. the warn-level handshake-retry
                // above) — explains a mid-game stall in the user's trail.
                logger.i { "Room socket reconnecting (code=$code, attempt=$attempt, backoff=${backoff}ms)" }
                _connection.emit(RoomConnection.Reconnecting(attempt, null))
                delay(backoff)
            }
        }

        /**
         * Drives one live session until it drops or completes a terminal
         * frame. Returns a [SessionOutcome] carrying whether the drop was
         * terminal (no reconnect) and whether the session ever delivered a
         * decodable frame (i.e. became healthy — drives the reconnect
         * counter reset). Owns its own `coroutineScope` so the writer is
         * structurally a child of this session and dies with it.
         */
        private suspend fun runConnectedSession(session: RoomSocketSession): SessionOutcome =
            coroutineScope {
                val writerJob = launch { drainOutbound(session) }
                var terminal = false
                var deliveredFrame = false
                try {
                    session.incoming.collect { text ->
                        val event = decode(text) ?: return@collect
                        deliveredFrame = true
                        // Debug-only one-liner per inbound wire frame. Rides
                        // only in the in-memory ring buffer dumped on feedback
                        // — never as breadcrumbs or events. Gives reported
                        // sessions a record of what the server actually said,
                        // which is the missing half of "the chips animated
                        // wrong when they bet."
                        logger.d { event.summary() }
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
                                _latestGameState.value = GameplayFrame.StateSnapshot(event.state)
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
                            // Live, ephemeral: route onto the replay-0
                            // stream so a mid-hand joiner never re-fires a
                            // stale reaction.
                            is RoomSocketEventDto.EmojiBlast ->
                                _gameplayFrames.emit(
                                    GameplayFrame.EmojiBlast(
                                        seatIndex = event.seatIndex,
                                        emoji = event.emoji,
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
                SessionOutcome(terminal = terminal, deliveredFrame = deliveredFrame)
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
     * Result of one connected session. [terminal] means the server told
     * us the room is gone (no reconnect); [deliveredFrame] means the
     * session decoded at least one frame before dropping (it was healthy,
     * so the reconnect counter resets). A half-open socket drops without
     * either flag set.
     */
    private data class SessionOutcome(val terminal: Boolean, val deliveredFrame: Boolean)

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

        /**
         * Consecutive reconnects (after a connect that never became
         * healthy) before the socket gives up with
         * [ClosedReason.ReconnectFailed]. Bounds the half-open-socket
         * storm so the client stops looping and the user can leave.
         */
        internal const val MAX_RECONNECT_ATTEMPTS: Int = 6

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

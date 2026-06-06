package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Server-driven [PokerSession]. The local side is a thin shell over
 * the room's [RoomConnectionHandle]:
 *
 *  - **State** comes from inbound `GameStateSnapshot`s — every snapshot
 *    overwrites [gameStateFlow] verbatim. Until the first snapshot
 *    lands the flow holds [emptyGameState], a sentinel the factory
 *    renders as `TableUiState.Loading`.
 *  - **Events** come from inbound `Event` frames into a replay-16
 *    [SharedFlow] so a subscriber attaching shortly after `run()`
 *    starts still catches the opening HandStarted / BlindPosted /
 *    HoleCardsDealt burst.
 *  - **Connection health** mirrors [RoomConnection] transitions.
 *  - **Submit** sends a [ClientFrame.SubmitIntent], suspends on a
 *    nonce-keyed [CompletableDeferred] until the matching
 *    [GameplayFrame.IntentAck] arrives, and throws
 *    [IntentRejectedException] on rejection or
 *    [IntentTimeoutException] after [INTENT_TIMEOUT_MS].
 *  - **requestNextHand** is fire-and-forget; the server's nonce
 *    dedupe collapses races between players.
 *
 * Lifecycle: the factory's `bootstrap()` calls [run] inside the VM's
 * scope, which launches the connection + gameplay-frame collectors and
 * suspends until cancelled. When the VM dies, the collectors die too;
 * the underlying [RoomConnectionHandle] shares one WS across the
 * lobby + play screen so the socket itself stays open as long as any
 * other consumer holds a collector.
 */
internal class RemotePokerSession(
    private val handle: RoomConnectionHandle,
) : PokerSession {

    private val logger = KLog.withTag("RemotePokerSession")

    private val _gameStateFlow = MutableStateFlow(emptyGameState)
    override val gameStateFlow: StateFlow<GameState> = _gameStateFlow.asStateFlow()

    /**
     * `replay = 16` — match the server's own buffer so a subscriber
     * that mounts right after the first snapshot still sees the
     * opening event burst. `extraBufferCapacity = 64` covers heavy
     * mid-hand action without backpressuring the WS reader.
     */
    private val _events = MutableSharedFlow<GameEvent>(
        replay = 16,
        extraBufferCapacity = 64,
    )
    override val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * `extraBufferCapacity = 1` so the terminal reason is buffered even
     * when the VM's collector attaches a beat after `run()` — a one-shot
     * `tryEmit` from [collectConnection] never drops it.
     */
    private val _roomClosed = MutableSharedFlow<ClosedReason>(extraBufferCapacity = 1)
    override val roomClosed: SharedFlow<ClosedReason> = _roomClosed.asSharedFlow()

    private val pendingAcks: MutableMap<String, CompletableDeferred<GameplayFrame.IntentAck>> =
        mutableMapOf()
    private val pendingAcksMutex = Mutex()

    /**
     * Conflated so a flurry of "next hand!" taps collapses to one
     * outbound frame — the server nonces, but we don't need to spam
     * the wire when the user impatiently double-taps.
     */
    private val nextHandSignal: Channel<Unit> = Channel(capacity = Channel.CONFLATED)

    /**
     * Drive the session. Suspends until the calling scope cancels.
     * Called by [RemotePokerSessionFactory.bootstrap].
     */
    suspend fun run() = coroutineScope {
        launch { collectConnection() }
        launch { collectGameplay() }
        launch { pumpNextHandSignals() }
    }

    private suspend fun collectConnection() {
        handle.connection.collect { conn ->
            _connectionState.value = when (conn) {
                RoomConnection.Connecting -> ConnectionState.Reconnecting
                is RoomConnection.Connected -> ConnectionState.Connected
                is RoomConnection.Reconnecting -> ConnectionState.Reconnecting
                is RoomConnection.Closed -> ConnectionState.Disconnected
            }
            // A terminal close (room GC'd / subscription rejected) collapses
            // to Disconnected above, which the banner can't distinguish from
            // a transient drop. Fan it out as a one-shot so the VM can pop
            // the screen rather than leave the user spinning. Cancelled is
            // our own teardown — the player is already leaving.
            if (conn is RoomConnection.Closed && conn.reason != ClosedReason.Cancelled) {
                _roomClosed.tryEmit(conn.reason)
            }
        }
    }

    private suspend fun collectGameplay() {
        handle.gameplayFrames.collect { frame ->
            when (frame) {
                is GameplayFrame.StateSnapshot -> {
                    _gameStateFlow.value = frame.state
                }
                is GameplayFrame.Event -> {
                    _events.tryEmit(frame.event)
                }
                is GameplayFrame.IntentAck -> resolvePendingAck(frame)
            }
        }
    }

    private suspend fun pumpNextHandSignals() {
        for (signal in nextHandSignal) {
            Catching { handle.send(ClientFrame.RequestNextHand(newNonce())) }
                .onFailure { e -> logger.w(e) { "requestNextHand send failed" } }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun submit(intent: PlayerIntent) {
        val nonce = newNonce()
        val deferred = CompletableDeferred<GameplayFrame.IntentAck>()
        pendingAcksMutex.withLock { pendingAcks[nonce] = deferred }
        try {
            handle.send(ClientFrame.SubmitIntent(intent, nonce))
            val ack = try {
                withTimeout(INTENT_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw IntentTimeoutException(
                    "no ack within ${INTENT_TIMEOUT_MS}ms for nonce=$nonce",
                )
            }
            if (!ack.accepted) {
                throw IntentRejectedException(ack.error ?: "unspecified")
            }
        } finally {
            // Drop the deferred whether we succeeded, timed out, or got
            // cancelled mid-await. Leaving the entry would leak per-
            // intent state across the session's lifetime.
            pendingAcksMutex.withLock { pendingAcks.remove(nonce) }
        }
    }

    override fun requestNextHand() {
        nextHandSignal.trySend(Unit)
    }

    private suspend fun resolvePendingAck(ack: GameplayFrame.IntentAck) {
        val deferred = pendingAcksMutex.withLock { pendingAcks[ack.clientNonce] }
        deferred?.complete(ack)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun newNonce(): String = Uuid.random().toString()

    internal companion object {
        /**
         * Per-intent ack timeout. A live MP server round-trips in
         * under 100ms on a healthy network; 10s is generous enough to
         * tolerate a brief reconnect mid-submit without forcing the
         * VM to surface a flicker.
         */
        const val INTENT_TIMEOUT_MS: Long = 10_000L

        /**
         * Pre-first-snapshot state. The factory's `tableFor` checks
         * `seats.isEmpty()` to render `TableUiState.Loading` until a
         * real snapshot arrives.
         */
        val emptyGameState: GameState = GameState(
            settings = RoomSettings.Default,
            handNumber = 0,
            buttonSeatIndex = 0,
            seats = emptyList(),
            community = emptyList(),
            street = BettingRound.Preflop,
            currentBetThisStreet = 0L,
            lastFullRaiseSize = 0L,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        )
    }
}

/**
 * Surfaced from [RemotePokerSession.submit] when the server's
 * [GameplayFrame.IntentAck] comes back with `accepted = false`. The
 * VM maps this to a UI-level "not your turn" / "illegal action"
 * surface; the user keeps their stack and the engine moves on.
 */
class IntentRejectedException(val reason: String) :
    RuntimeException("intent rejected: $reason")

/**
 * Surfaced from [RemotePokerSession.submit] when no ack arrives
 * within [RemotePokerSession.INTENT_TIMEOUT_MS]. Either the WS is in
 * the middle of a long reconnect or the server crashed; either way
 * the user's action did not land.
 */
class IntentTimeoutException(message: String) : RuntimeException(message)

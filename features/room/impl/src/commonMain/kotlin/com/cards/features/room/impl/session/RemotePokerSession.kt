package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.features.room.impl.TableUiState

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
    /**
     * The local player's user id, matched against `RoomMember.userId` to
     * decide when they've become the last human at the table. Defaults to
     * blank for tests that don't exercise presence (it simply never matches a
     * real member, so the opponents-left signal stays dormant).
     */
    private val localUserId: String = "",
    /**
     * Sends the durable room-leave (the HTTP DELETE) when the player
     * exits. Injected as a lambda so the session stays decoupled from
     * `RoomRepository` + the room code, which only the factory holds.
     */
    private val onLeave: suspend () -> Unit = {},
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

    /**
     * `replay = 0` — an emote is a live reaction. A subscriber that
     * mounts mid-hand must not see a burst of stale blasts replay.
     */
    private val _emoteBlasts = MutableSharedFlow<RemoteEmote>(extraBufferCapacity = 32)
    override val emoteBlasts: SharedFlow<RemoteEmote> = _emoteBlasts.asSharedFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * `replay = 1` so the terminal reason survives for a collector that
     * attaches after `run()` has already fanned the close out. The VM's
     * roomClosed collector is a sibling launch of the connection collector,
     * so a close racing session bootstrap can fire before the collector
     * exists — `extraBufferCapacity` alone would drop it (a buffered value
     * with no subscriber and `replay = 0` is evicted, never replayed), and
     * the user would sit on a Disconnected banner with nothing popping the
     * screen. The reason is terminal and idempotent, so replaying it once
     * to a late collector is exactly the desired behaviour.
     */
    private val _roomClosed = MutableSharedFlow<ClosedReason>(replay = 1)
    override val roomClosed: SharedFlow<ClosedReason> = _roomClosed.asSharedFlow()

    /**
     * `replay = 1` so a collector that mounts after the transition still sees
     * it (mirrors [roomClosed]). Fired at most once per session — see
     * [opponentsAlreadyLeft].
     */
    private val _opponentsLeft = MutableSharedFlow<Unit>(replay = 1)
    override val opponentsLeft: SharedFlow<Unit> = _opponentsLeft.asSharedFlow()

    // Last observed count of human (non-bot) room members. Seeded to -1 so the
    // first snapshot establishes a baseline without ever reading as a "drop."
    private var previousHumanCount: Int = -1
    private var opponentsAlreadyLeft: Boolean = false

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
            val previous = _connectionState.value
            val next = when (conn) {
                RoomConnection.Connecting -> ConnectionState.Reconnecting
                is RoomConnection.Connected -> ConnectionState.Connected
                is RoomConnection.Reconnecting -> ConnectionState.Reconnecting
                is RoomConnection.Closed -> ConnectionState.Disconnected
            }
            _connectionState.value = next

            // Detect "all other opponents left": the human (non-bot) member
            // count drops to the local player alone, after having been 2+. Only
            // a Connected snapshot carries the live member list.
            if (conn is RoomConnection.Connected) {
                val humans = conn.room.members.count { !it.isBot }
                val iAmStillSeated = conn.room.members.any { it.userId == localUserId }
                if (!opponentsAlreadyLeft && iAmStillSeated &&
                    previousHumanCount >= 2 && humans <= 1
                ) {
                    opponentsAlreadyLeft = true
                    _opponentsLeft.tryEmit(Unit)
                }
                previousHumanCount = humans
            }
            // Info: connection lifecycle is the backbone of reconstructing a
            // reported MP session — it rides in release breadcrumbs and, with
            // the close reason, explains "the game just froze/dropped."
            if (next != previous) {
                logger.i {
                    "Connection $previous → $next" +
                        (if (conn is RoomConnection.Closed) " (reason=${conn.reason})" else "")
                }
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
                    if (isStale(incoming = frame.state, current = _gameStateFlow.value)) {
                        logger.d {
                            "dropping stale snapshot hand=${frame.state.handNumber}/" +
                                "seq=${frame.state.lastSequence} behind applied hand=" +
                                "${_gameStateFlow.value.handNumber}/seq=${_gameStateFlow.value.lastSequence}"
                        }
                    } else {
                        // Info once when the table goes from Loading to a real
                        // hand — marks "client had enough state to play," the
                        // readiness milestone in a session trail. Per-snapshot
                        // applies stay silent (too noisy); the stale-drop above
                        // is debug.
                        val wasLoading = _gameStateFlow.value.seats.isEmpty()
                        _gameStateFlow.value = frame.state
                        if (wasLoading && frame.state.seats.isNotEmpty()) {
                            logger.i {
                                "Game state ready: hand=${frame.state.handNumber}, " +
                                    "seats=${frame.state.seats.size}, acting=${frame.state.actingSeatIndex}"
                            }
                        }
                    }
                }
                is GameplayFrame.Event -> {
                    _events.tryEmit(frame.event)
                }
                is GameplayFrame.IntentAck -> resolvePendingAck(frame)
                is GameplayFrame.EmojiBlast ->
                    _emoteBlasts.tryEmit(RemoteEmote(seatIndex = frame.seatIndex, emoji = frame.emoji))
            }
        }
    }

    /**
     * The transport (per the decision log) doesn't guarantee snapshot
     * order beyond the engine's sequence numbers, so a frame that arrives
     * after a newer one — e.g. an old connection's buffered snapshot
     * landing just after the post-reconnect resync — must not clobber the
     * live table. [GameState.lastSequence] resets to 0 at the start of
     * every hand (the engine seeds `seq = 0L` in `startHand`), so it only
     * orders frames *within* a hand; across hands [GameState.handNumber]
     * is the monotonic key. A snapshot is stale iff it sits strictly
     * behind the applied state on `(handNumber, lastSequence)`. Equal keys
     * (an idempotent resync of the same state) still apply — dropping
     * those would risk swallowing a legitimate re-send.
     */
    private fun isStale(incoming: GameState, current: GameState): Boolean =
        incoming.handNumber < current.handNumber ||
            (incoming.handNumber == current.handNumber &&
                incoming.lastSequence < current.lastSequence)

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
        val action = intent::class.simpleName
        try {
            // Debug: per-action, only wanted when zooming into a specific hand.
            logger.d { "Submitting intent $action nonce=$nonce" }
            handle.send(ClientFrame.SubmitIntent(intent, nonce))
            val ack = try {
                withTimeout(INTENT_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                // Warn: the user's action silently didn't land — a top suspect
                // for "I tapped fold and nothing happened."
                logger.w { "Intent $action timed out after ${INTENT_TIMEOUT_MS}ms (nonce=$nonce)" }
                throw IntentTimeoutException(
                    "no ack within ${INTENT_TIMEOUT_MS}ms for nonce=$nonce",
                )
            }
            if (!ack.accepted) {
                // Info: a server "no" is player-visible and session-meaningful
                // (not-your-turn, illegal action, desync) — keep it in the trail.
                logger.i { "Intent $action rejected: ${ack.error ?: "unspecified"} (nonce=$nonce)" }
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

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun rebuy() {
        // Ack round-trip like submit() (not fire-and-forget like
        // requestNextHand): the caller needs to learn if the wallet couldn't
        // cover the buy-in so it can route the player to the quick-buy sheet.
        val nonce = newNonce()
        val deferred = CompletableDeferred<GameplayFrame.IntentAck>()
        pendingAcksMutex.withLock { pendingAcks[nonce] = deferred }
        try {
            logger.d { "Submitting rebuy nonce=$nonce" }
            handle.send(ClientFrame.Rebuy(nonce))
            val ack = try {
                withTimeout(INTENT_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                logger.w { "Rebuy timed out after ${INTENT_TIMEOUT_MS}ms (nonce=$nonce)" }
                throw IntentTimeoutException("no ack within ${INTENT_TIMEOUT_MS}ms for nonce=$nonce")
            }
            if (!ack.accepted) {
                logger.i { "Rebuy rejected: ${ack.error ?: "unspecified"} (nonce=$nonce)" }
                throw IntentRejectedException(ack.error ?: "unspecified")
            }
        } finally {
            pendingAcksMutex.withLock { pendingAcks.remove(nonce) }
        }
    }

    override suspend fun leave() {
        Catching { onLeave() }
            .onFailure { e -> logger.w(e) { "room leave send failed" } }
    }

    override suspend fun sendEmote(emoji: String) {
        handle.send(ClientFrame.SendEmoji(emoji = emoji, clientNonce = newNonce()))
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

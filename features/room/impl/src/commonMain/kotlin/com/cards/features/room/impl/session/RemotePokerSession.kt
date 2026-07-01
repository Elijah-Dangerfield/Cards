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
    /**
     * Fired once per finished hand with the terminating [GameEvent.HandEnded],
     * the latest [GameState], and the local human's stack at the start of that
     * hand — the same contract [LocalBotsSession] honours. The VM's
     * `handleHandEnded` is the only caller of `awardForHand` / `recordPlayerStat`
     * / the hand-end celebration, so without this MP hands silently award no XP
     * and record no player-stat (PROG-4). Defaults to a no-op for tests that
     * don't exercise progression.
     */
    private val onHandEnded: (GameEvent.HandEnded, GameState, Long) -> Unit = { _, _, _ -> },
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

    /**
     * `replay = 0` — a "someone left" notice is a live, momentary event; a
     * collector mounting later (a screen re-subscribe) must not re-fire a
     * stale departure. `extraBufferCapacity` covers a burst of simultaneous
     * leaves landing in one snapshot.
     */
    private val _opponentLeft = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val opponentLeft: SharedFlow<String> = _opponentLeft.asSharedFlow()

    /**
     * `replay = 0` — a live notice; a late collector must not re-fire a stale
     * rejection. Emitted when a [requestNextHand] frame is rejected, carrying the
     * classified [NextHandRefusal] reason so the VM can split the terminal
     * heads-up-bust case from a transient resync race (MP-22).
     */
    private val _nextHandRefused = MutableSharedFlow<NextHandRefusal>(extraBufferCapacity = 4)
    override val nextHandRefused: SharedFlow<NextHandRefusal> = _nextHandRefused.asSharedFlow()

    /**
     * Live heads-up rebuy-grace countdown (MP-14). Set on a `MatchOverPending`
     * frame, cleared on `MatchOverCleared` (the busted player rebought, the table
     * resumes). A terminal resolve closes the connection instead, so the screen
     * routes off — it never lands here.
     */
    private val _matchOverCountdown = MutableStateFlow<MatchOverCountdown?>(null)
    override val matchOverCountdown: StateFlow<MatchOverCountdown?> =
        _matchOverCountdown.asStateFlow()

    /**
     * Live between-hands auto-advance countdown. Set on a `NextHandPending` frame,
     * cleared on `NextHandCleared` and whenever a live-hand snapshot lands (the
     * next hand dealt). The screen renders it on real-chip tables as the
     * leave-with-winnings window; practice tables ignore it and keep their dialog.
     */
    private val _nextHandCountdown = MutableStateFlow<NextHandCountdown?>(null)
    override val nextHandCountdown: StateFlow<NextHandCountdown?> =
        _nextHandCountdown.asStateFlow()

    /**
     * Host-chosen table cosmetics (SHOP-3), pinned from the room snapshot's
     * [com.dangerfield.cards.libraries.rooms.Room.feltProductId] /
     * [com.dangerfield.cards.libraries.rooms.Room.cardBackProductId]. Set on the
     * first Connected snapshot that carries a non-null override and held for the
     * session — the values are constant for the room's life, and a placeholder
     * presence frame (member-list convergence) delivers them as null, so we never
     * regress a known override back to "use your own."
     */
    private val _tableCosmetics = MutableStateFlow<TableCosmetics?>(null)
    override val tableCosmetics: StateFlow<TableCosmetics?> = _tableCosmetics.asStateFlow()

    // Last observed human (non-bot) room members, keyed by user id → display
    // name. Null until the first snapshot establishes a baseline, so the first
    // snapshot never reads as a "drop."
    private var previousHumans: Map<String, String>? = null
    private var opponentsAlreadyLeft: Boolean = false

    // The local human's stack as of the most recently observed hand-start
    // snapshot, fed to [onHandEnded] so the hand-end achievement context can
    // tell start-vs-end stack (e.g. "doubled up"). Captured when a snapshot
    // for a not-yet-seen hand lands — by then the engine has reset stacks for
    // the new hand, so it reflects the true starting stack.
    private var humanStackAtHandStart: Long = 0L
    private var humanStackHand: Int = 0

    // Snapshot of the table the last time we observed a hand in progress, so a
    // HandEnded event that races ahead of (or arrives without) a fresh snapshot
    // still resolves the human's seat + stacks. The live [_gameStateFlow] is the
    // source of truth; this just guarantees onHandEnded never sees empty seats.
    private var lastNonEmptyState: GameState = emptyGameState

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

            // Diff the human (non-bot) member set across snapshots to surface
            // departures. Only a Connected snapshot carries the live member list.
            //
            //  - Terminal "all opponents left" (count drops to the local player
            //    alone after 2+): fire [opponentsLeft] once — the screen routes
            //    the lone player off the dead table.
            //  - A non-last opponent leaving (a known human disappears while 2+
            //    humans remain): fire [opponentLeft] with their name so the
            //    screen shows a transient notice and the seat renders vacated.
            if (conn is RoomConnection.Connected) {
                // SHOP-3: pin the host's table cosmetics off the first snapshot that
                // carries them. A placeholder presence frame delivers them as null,
                // so only adopt non-null values and never regress a known override.
                val felt = conn.room.feltProductId
                val cardBack = conn.room.cardBackProductId
                if (felt != null || cardBack != null) {
                    val current = _tableCosmetics.value
                    _tableCosmetics.value = TableCosmetics(
                        feltProductId = felt ?: current?.feltProductId,
                        cardBackProductId = cardBack ?: current?.cardBackProductId,
                    )
                }
                val humans = conn.room.members
                    .filter { !it.isBot }
                    .associate { it.userId to it.displayName }
                val iAmStillSeated = humans.containsKey(localUserId)
                val previous = previousHumans
                if (previous != null && iAmStillSeated) {
                    val departed = previous.keys - humans.keys
                    if (!opponentsAlreadyLeft && previous.size >= 2 && humans.size <= 1) {
                        opponentsAlreadyLeft = true
                        _opponentsLeft.tryEmit(Unit)
                    } else {
                        departed.forEach { id ->
                            previous[id]?.let { name -> _opponentLeft.tryEmit(name) }
                        }
                    }
                }
                previousHumans = humans
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
            // Debug: a "what reached the dispatcher" companion to the socket
            // layer's "recv" log. The socket log covers raw wire frames
            // including lobby/presence; this one is gameplay only, after the
            // staleness filter — useful when reconstructing what the UI was
            // actually told about. Lives only in the ring-buffer dump.
            logger.d { "apply ${frame.summary()}" }
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
                        // A live-hand snapshot means the next hand dealt — clear any
                        // between-hands countdown (belt-and-suspenders alongside the
                        // server's NextHandCleared, in case it's dropped/raced).
                        if (frame.state.street != BettingRound.Complete) {
                            _nextHandCountdown.value = null
                        }
                        _gameStateFlow.value = frame.state
                        if (frame.state.seats.isNotEmpty()) {
                            lastNonEmptyState = frame.state
                            captureHandStartStack(frame.state)
                        }
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
                    // The VM's progression path (XP award, player-stat record,
                    // hand-end celebration) only runs through onHandEnded; the
                    // GameEventReceived collector projects the table but never
                    // calls it. Fire it here off the same wire HandEnded the
                    // collector sees, so MP hands credit like solo bots (PROG-4).
                    val event = frame.event
                    if (event is GameEvent.HandEnded) {
                        val state = currentEndOfHandState()
                        if (state.seats.isNotEmpty()) {
                            onHandEnded(event, state, humanStackAtHandStart)
                        }
                    }
                }
                is GameplayFrame.IntentAck -> resolvePendingAck(frame)
                is GameplayFrame.EmojiBlast ->
                    _emoteBlasts.tryEmit(RemoteEmote(seatIndex = frame.seatIndex, emoji = frame.emoji))
                is GameplayFrame.MatchOverPending -> {
                    val localSeat = _gameStateFlow.value.seats
                        .firstOrNull { it.playerId == localUserId }?.index
                    _matchOverCountdown.value = MatchOverCountdown(
                        deadlineEpochMs = frame.deadlineEpochMs,
                        localPlayerIsBusted = localSeat != null && localSeat == frame.bustedSeatIndex,
                    )
                }
                GameplayFrame.MatchOverCleared -> _matchOverCountdown.value = null
                is GameplayFrame.NextHandPending ->
                    _nextHandCountdown.value = NextHandCountdown(deadlineEpochMs = frame.deadlineEpochMs)
                GameplayFrame.NextHandCleared -> _nextHandCountdown.value = null
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

    /**
     * Record the local human's stack the first time we see a snapshot for a new
     * hand. The server resets stacks at hand-start, so the earliest snapshot of
     * a hand carries the true starting stack. Keyed on [GameState.handNumber] so
     * later in-hand snapshots (post-bet) don't overwrite it.
     */
    private fun captureHandStartStack(state: GameState) {
        if (state.handNumber <= humanStackHand) return
        humanStackHand = state.handNumber
        humanStackAtHandStart = state.seats
            .firstOrNull { it.playerId == localUserId }?.stack ?: 0L
    }

    /**
     * The best available end-of-hand state for [onHandEnded]: the live snapshot
     * when it carries seats, else the last non-empty one we saw. Event and
     * snapshot frames ride independent flows with no ordering guarantee, so a
     * HandEnded can arrive a tick before its settling snapshot — falling back to
     * the prior snapshot keeps the human's seat resolvable rather than handing
     * the VM empty seats (which it reads as "spectator", crediting nothing).
     */
    private fun currentEndOfHandState(): GameState {
        val live = _gameStateFlow.value
        return if (live.seats.isNotEmpty()) live else lastNonEmptyState
    }

    private suspend fun pumpNextHandSignals() {
        for (signal in nextHandSignal) {
            sendNextHand()
        }
    }

    private suspend fun sendNextHand() {
        val nonce = newNonce()
        val deferred = CompletableDeferred<GameplayFrame.IntentAck>()
        pendingAcksMutex.withLock { pendingAcks[nonce] = deferred }
        try {
            val sent = Catching { handle.send(ClientFrame.RequestNextHand(nonce)) }
                .onFailure { e -> logger.w(e) { "requestNextHand send failed" } }
                .isSuccess
            if (!sent) return
            // Unlike submit()/rebuy() this stays fire-and-forget to the caller,
            // but we still watch the ack so a server "no" surfaces as a notice
            // instead of the winner's tap vanishing silently (MP-14). A missing
            // ack just times out quietly — the prior behaviour.
            val ack = try {
                withTimeout(INTENT_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                logger.w { "requestNextHand timed out after ${INTENT_TIMEOUT_MS}ms (nonce=$nonce)" }
                return
            }
            if (!ack.accepted) {
                val refusal = classifyNextHandRefusal(ack.error)
                // Info: which refusal class we read from the server error — the
                // branch that decides between the terminal rebuy notice and a
                // transient resync (MP-22). Once per refusal, not in a loop.
                logger.i {
                    "requestNextHand rejected: ${ack.error ?: "unspecified"} → $refusal (nonce=$nonce)"
                }
                _nextHandRefused.tryEmit(refusal)
            }
        } finally {
            pendingAcksMutex.withLock { pendingAcks.remove(nonce) }
        }
    }

    /**
     * Map the server's free-text next-hand rejection onto a [NextHandRefusal]
     * (MP-22). The server only refuses next-hand for the genuine can't-deal case
     * with [CANNOT_DEAL_ERROR]; every other reason ("current hand not complete",
     * a stale-snapshot race, anything unexpected) is a transient that the live
     * snapshot stream resolves on its own — so it must not surface the terminal
     * rebuy copy.
     */
    private fun classifyNextHandRefusal(error: String?): NextHandRefusal =
        if (error == CANNOT_DEAL_ERROR) NextHandRefusal.CannotDeal else NextHandRefusal.Transient

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
         * The server's exact next-hand rejection reason for "the table genuinely
         * can't deal another hand" (heads-up bust, nobody else has chips). Lives
         * in `GameSession.requestNextHand` server-side. Any *other* rejection
         * reason is treated as a transient resync race (MP-22) — see
         * [classifyNextHandRefusal]. Mirror string, not a shared type: the wire
         * carries free-text errors today, so this is the one coupling point.
         */
        const val CANNOT_DEAL_ERROR: String = "not enough players with chips for next hand"

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

private fun GameplayFrame.summary(): String = when (this) {
    is GameplayFrame.StateSnapshot ->
        "StateSnapshot hand=${state.handNumber} street=${state.street} " +
            "acting=${state.actingSeatIndex} seq=${state.lastSequence}"
    is GameplayFrame.Event -> "Event ${event::class.simpleName} seq=$seq"
    is GameplayFrame.IntentAck ->
        "IntentAck accepted=$accepted nonce=$clientNonce" +
            (error?.let { " error=$it" } ?: "")
    is GameplayFrame.EmojiBlast -> "EmojiBlast seat=$seatIndex emoji=$emoji"
    is GameplayFrame.MatchOverPending ->
        "MatchOverPending busted=$bustedSeatIndex deadline=$deadlineEpochMs"
    GameplayFrame.MatchOverCleared -> "MatchOverCleared"
    is GameplayFrame.NextHandPending -> "NextHandPending deadline=$deadlineEpochMs"
    GameplayFrame.NextHandCleared -> "NextHandCleared"
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

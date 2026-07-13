package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.RemotePokerSessionFactory

import com.dangerfield.cards.libraries.cards.Telemetry
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.DispatcherProvider
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Pot
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RemoveBotOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Clock

/**
 * Builds a multiplayer scenario. Server state is authoritative, so opponent
 * actions are expressed as the frames the server would send — a [GameState]
 * snapshot (drives legal actions / acting seat) plus, when a pill should show,
 * a [GameEvent.ActionTaken] (the VM derives action pills from events, exactly
 * like solo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MpScenarioBuilder(
    private val scope: TestScope,
    private val dispatchers: DispatcherProvider,
    private val localUserId: String,
) {
    private var handle: FakeRoomConnectionHandle = FakeRoomConnectionHandle()
    private var server: FakeRoomServer? = null
    private val friendRepository = FakeFriendRepository()
    private val chipsRepository = FakeChipsRepository()
    private val leaveCashOutNotifier = FakeLeaveCashOutNotifier()

    // The balance the server's synchronous leave cash-out returns (MP-29). Null
    // (default) models a leave that settled nothing → the VM falls back to a sync.
    private var leaveSettledBalance: Long? = null

    /** Pin the settled balance the DELETE /me leave returns (MP-29). */
    fun withLeaveSettledBalance(balance: Long?): MpScenarioBuilder = apply {
        leaveSettledBalance = balance
    }

    /** Pre-seed frames before the VM mounts (e.g. the subscribe-after-deal case). */
    internal suspend fun preSeed(block: suspend FakeRoomConnectionHandle.() -> Unit): MpScenarioBuilder = apply {
        handle.block()
    }

    /**
     * Back the scenario with a [FakeRoomServer] that drives a real [GameEngine],
     * so the test can play full turn cycles (`iSubmit` runs the engine and the
     * server fans real snapshots + events back) instead of hand-feeding every
     * server frame. The local user must be one of [occupants].
     */
    fun withServer(
        occupants: List<ServerSeat>,
        settings: com.dangerfield.cards.libraries.gameplay.RoomSettings =
            com.dangerfield.cards.libraries.gameplay.RoomSettings.Default,
        deckFactory: ((handNumber: Int) -> com.dangerfield.cards.libraries.gameplay.Deck)? = null,
        opponentPolicy: OpponentPolicy = OpponentPolicy.CheckOrCall,
    ): MpScenarioBuilder = apply {
        server = if (deckFactory != null) {
            FakeRoomServer(
                occupants = occupants,
                settings = settings,
                deckFactory = deckFactory,
                localUserId = localUserId,
                opponentPolicy = opponentPolicy,
            )
        } else {
            FakeRoomServer(
                occupants = occupants,
                settings = settings,
                localUserId = localUserId,
                opponentPolicy = opponentPolicy,
            )
        }
    }

    suspend fun start(): RunningMpScenario {
        val connectionHandle: RoomConnectionHandle = server ?: handle
        val factory = RemotePokerSessionFactory(
            roomCode = "ABCDEF",
            localUserId = localUserId,
            isPublicTable = false,
            roomRepository = HarnessRoomRepository(
                handle = connectionHandle,
                leaveOutcome = { LeaveRoomOutcome.Success(settledBalance = leaveSettledBalance) },
            ),
            telemetry = HarnessTelemetry,
        )
        val vm = PlayPokerViewModel(
            sessionFactory = factory,
            progressionRepository = FakeProgressionRepository(),
            playStyleRepository = FakePlayStyleRepository(),
            playerStatsRepository = FakePlayerStatsRepository(),
            progressionConfig = FakeProgressionConfig(),
            achievementRepository = FakeAchievementRepository(),
            appCache = FakeAppCache(),
            equipmentRepository = FakeEquipmentRepository(),
            inventoryRepository = FakeInventoryRepository(),
            productsRepository = FakeProductsRepository(),
            chipsRepository = chipsRepository,
            purchaseChipPack = FakePurchaseChipPackUseCase(),
            profileRepository = FakeProfileRepository(),
            friendRepository = friendRepository,
            reviewPromptCoordinator = FakeReviewPromptCoordinator(),
            leaveCashOutNotifier = leaveCashOutNotifier,
            dispatcherProvider = dispatchers,
            appScope = AppCoroutineScope(dispatchers),
            clock = Clock.System,
            socialEnabledConfig = com.dangerfield.cards.libraries.social.SocialEnabled.forTest(enabled = false),
        )
        val recorder = EventRecorder().also { it.attach(scope.backgroundScope, vm) }
        scope.advanceUntilIdle()
        return RunningMpScenario(
            vm, handle, server, recorder, scope, friendRepository, chipsRepository, leaveCashOutNotifier,
        )
    }
}

/**
 * A started MP scenario. `serverXxx` methods inject server frames; `iSubmitAndAck`
 * sends a player intent and acks it so the submit coroutine doesn't sit on its
 * 10s timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunningMpScenario internal constructor(
    val vm: PlayPokerViewModel,
    internal val handle: FakeRoomConnectionHandle,
    private val server: FakeRoomServer?,
    val events: EventRecorder,
    private val scope: TestScope,
    val friendRepository: FakeFriendRepository,
    val chipsRepository: FakeChipsRepository,
    val leaveCashOutNotifier: FakeLeaveCashOutNotifier,
) {
    private var seq: Long = 0L

    val table: TableUiState.Active
        get() = vm.state.table as? TableUiState.Active
            ?: error("table is ${vm.state.table::class.simpleName}, not Active yet")

    val connection: ConnectionState get() = vm.state.connection

    /**
     * Server-backed play: deal the opening hand by sending [ClientFrame.StartHand]
     * through the [FakeRoomServer]. Only valid on a `withServer(...)` scenario.
     */
    suspend fun serverStartHand() {
        requireServer()
        vm.takeAction(PlayPokerAction.RequestNextHand)
        scope.advanceUntilIdle()
    }

    /**
     * Server-backed play: submit the local player's intent and let the
     * [FakeRoomServer] run the real engine, fanning the resulting snapshot +
     * events back. Only valid on a `withServer(...)` scenario.
     */
    suspend fun iSubmit(intent: PlayerIntent) {
        requireServer()
        vm.takeAction(PlayPokerAction.Submit(intent))
        scope.advanceUntilIdle()
    }

    /** The server's authoritative [GameState]. Only valid on a `withServer(...)` scenario. */
    val serverState: GameState
        get() = requireServer().currentState ?: error("no hand dealt yet — call serverStartHand()")

    /** Frames the session sent to the [FakeRoomServer]. Only valid on a `withServer(...)` scenario. */
    fun serverReceived(): List<ClientFrame> = requireServer().received

    private fun requireServer(): FakeRoomServer =
        server ?: error("this scenario has no FakeRoomServer — build it with mpScenario().withServer(...)")

    /** Arm (or, with null, disarm) a pre-action from the waiting bar (GAME-30). */
    suspend fun arm(preAction: PreAction?) {
        vm.takeAction(PlayPokerAction.SetPreAction(preAction))
        scope.advanceUntilIdle()
    }

    suspend fun serverSnapshot(state: GameState) {
        handle.pushFrame(GameplayFrame.StateSnapshot(state))
        scope.advanceUntilIdle()
    }

    suspend fun serverEvent(event: GameEvent) {
        handle.pushFrame(GameplayFrame.Event(seq = ++seq, event = event))
        scope.advanceUntilIdle()
    }

    suspend fun serverConnection(state: RoomConnection) {
        handle.pushConnection(state)
        scope.advanceUntilIdle()
    }

    /**
     * Server opens the heads-up rebuy-grace window (MP-14): the busted seat has
     * until [deadlineEpochMs] to rebuy. Mirrors the `MatchOverPending` wire frame.
     */
    suspend fun serverMatchOverPending(deadlineEpochMs: Long, bustedSeatIndex: Int) {
        handle.pushFrame(
            GameplayFrame.MatchOverPending(
                deadlineEpochMs = deadlineEpochMs,
                bustedSeatIndex = bustedSeatIndex,
            ),
        )
        scope.advanceUntilIdle()
    }

    /** Server clears the grace window — the busted seat rebought, play resumes. */
    suspend fun serverMatchOverCleared() {
        handle.pushFrame(GameplayFrame.MatchOverCleared)
        scope.advanceUntilIdle()
    }

    /**
     * Server opens the between-hands auto-advance countdown: the next hand is held
     * until [deadlineEpochMs]. Mirrors the `NextHandPending` wire frame — on a
     * real-chip table the screen renders the "Next hand in 0:0X" leave-with-winnings
     * window against it.
     */
    suspend fun serverNextHandPending(deadlineEpochMs: Long) {
        handle.pushFrame(GameplayFrame.NextHandPending(deadlineEpochMs = deadlineEpochMs))
        scope.advanceUntilIdle()
    }

    /** Server clears the between-hands countdown (the next hand dealt, or the advance was cancelled). */
    suspend fun serverNextHandCleared() {
        handle.pushFrame(GameplayFrame.NextHandCleared)
        scope.advanceUntilIdle()
    }

    /**
     * Server resolves the match-over: the grace expired, [winnerUserId] took the
     * table. Arrives as a terminal connection close (the socket layer maps the
     * `MatchOverResolved` wire frame to [ClosedReason.MatchOver]).
     */
    suspend fun serverMatchOverResolved(winnerUserId: String) {
        handle.pushConnection(
            RoomConnection.Closed(
                com.dangerfield.cards.libraries.rooms.ClosedReason.MatchOver(winnerUserId),
            ),
        )
        scope.advanceUntilIdle()
    }

    /**
     * Apply an opponent action: emit the [GameEvent.ActionTaken] (drives the
     * pill) then the resulting [GameState] snapshot (drives legal actions /
     * acting seat), the way the server fans both out.
     */
    suspend fun opponentActs(action: GameEvent.ActionTaken, resultingState: GameState) {
        handle.pushFrame(GameplayFrame.Event(seq = ++seq, event = action))
        handle.pushFrame(GameplayFrame.StateSnapshot(resultingState))
        scope.advanceUntilIdle()
    }

    fun sentFrames(): List<ClientFrame> = handle.sent
    fun lastSent(): ClientFrame = handle.sent.last()

    suspend fun iSubmitAndAck(
        intent: PlayerIntent,
        accepted: Boolean = true,
        error: String? = null,
    ) {
        vm.takeAction(PlayPokerAction.Submit(intent))
        scope.runCurrent()
        val sent = handle.sent.filterIsInstance<ClientFrame.SubmitIntent>().last()
        handle.pushFrame(
            GameplayFrame.IntentAck(clientNonce = sent.clientNonce, accepted = accepted, error = error),
        )
        scope.advanceUntilIdle()
    }

    /**
     * Submit an intent and never ack it, then drain virtual time past the
     * session's intent timeout — the submit coroutine throws
     * [com.dangerfield.cards.features.room.impl.session.IntentTimeoutException]
     * and the VM surfaces the timed-out hint. Mirrors a server that went silent
     * mid-submit (MP-20).
     */
    suspend fun iSubmitAndLetTimeOut(intent: PlayerIntent) {
        vm.takeAction(PlayPokerAction.Submit(intent))
        scope.advanceUntilIdle()
    }

    /**
     * Server refuses a [requestNextHand] — fans an unaccepted ack for the
     * outbound [ClientFrame.RequestNextHand] frame. The [error] string decides
     * the classification (MP-22): the canonical can't-deal reason drives
     * [PlayPokerEvent.NextHandUnavailable] (the rebuy notice), any other reason
     * drives [PlayPokerEvent.NextHandResyncing] (a transient resync hint).
     */
    suspend fun serverRefusesNextHand(error: String? = null) {
        vm.takeAction(PlayPokerAction.RequestNextHand)
        scope.runCurrent()
        val sent = handle.sent.filterIsInstance<ClientFrame.RequestNextHand>().last()
        handle.pushFrame(
            GameplayFrame.IntentAck(clientNonce = sent.clientNonce, accepted = false, error = error),
        )
        scope.advanceUntilIdle()
    }
}

// ---------- MP snapshot builders ----------

private val mpSettings = RoomSettings.Default

fun mpSeat(
    index: Int,
    playerId: String,
    displayName: String = "P$index",
    stack: Long = 1_000,
    contributedThisStreet: Long = 0,
    holeCards: List<Card> = emptyList(),
    participation: HandParticipation = HandParticipation.InHand,
    isBot: Boolean = false,
): Seat = Seat(
    index = index,
    playerId = playerId,
    displayName = displayName,
    stack = stack,
    seatStatus = SeatStatus.Active,
    handParticipation = participation,
    isBot = isBot,
    contributedThisStreet = contributedThisStreet,
    holeCards = holeCards,
)

fun mpTable(
    seats: List<Seat>,
    actingSeatIndex: Int?,
    currentBetThisStreet: Long = 0,
    lastFullRaiseSize: Long = mpSettings.bigBlind,
    street: BettingRound = BettingRound.Preflop,
    community: List<Card> = emptyList(),
    handNumber: Int = 1,
    lastSequence: Long = 0,
    buttonSeatIndex: Int = 0,
    pots: List<Pot> = emptyList(),
): GameState = GameState(
    settings = mpSettings,
    handNumber = handNumber,
    buttonSeatIndex = buttonSeatIndex,
    seats = seats,
    community = community,
    street = street,
    currentBetThisStreet = currentBetThisStreet,
    lastFullRaiseSize = lastFullRaiseSize,
    actingSeatIndex = actingSeatIndex,
    deckRemaining = emptyList(),
    pots = pots,
    lastSequence = lastSequence,
)

// ---------- Minimal collaborators ----------

/**
 * Only [connect] + [leaveRoom] are exercised by the MP factory; the rest belong
 * to the lobby. [leaveOutcome] models the server's leave response — MP-29 has the
 * DELETE cash out synchronously and return the settled balance, so a test can pin
 * whether the play-screen leave applies that balance directly vs falls back to a
 * sync.
 */
private class HarnessRoomRepository(
    private val handle: RoomConnectionHandle,
    private val leaveOutcome: () -> LeaveRoomOutcome = { LeaveRoomOutcome.Success() },
) : RoomRepository {
    override suspend fun createRoom(
        maxSeats: Int?,
        buyIn: Long?,
        open: Boolean,
        feltProductId: String?,
        cardBackProductId: String?,
    ): CreateRoomOutcome = error("unused")
    override suspend fun joinRoom(code: String): JoinRoomOutcome = error("unused")
    override suspend fun leaveRoom(code: String): LeaveRoomOutcome = leaveOutcome()
    override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome = error("unused")
    override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome = error("unused")
    override suspend fun getActiveRooms(): GetActiveRoomsOutcome = error("unused")
    override fun observeActiveRooms(): Flow<List<Room>> = error("unused")
    override fun connect(code: String): RoomConnectionHandle = handle
}

private object HarnessTelemetry : Telemetry {
    override fun initialize() = Unit
    override fun setUser(email: String?, name: String?, id: String?) = Unit
    override fun setCurrentRoute(route: String) = Unit
    override fun setSession(sessionId: String) = Unit
    override fun setInstallId(installId: String) = Unit
    override fun setRoom(code: String?) = Unit
    override fun setSeat(seatIndex: Int?) = Unit
    override fun setHand(handNumber: Int?) = Unit
    override fun setOpponents(userIds: List<String>?) = Unit
    override fun setMpStateProvider(provider: (() -> String?)?) = Unit
    override fun captureUserFeedback(
        message: String,
        isBugReport: Boolean,
        eventId: String?,
        errorCode: Int?,
        email: String?,
        screenshots: List<ByteArray>,
    ) = Unit
}

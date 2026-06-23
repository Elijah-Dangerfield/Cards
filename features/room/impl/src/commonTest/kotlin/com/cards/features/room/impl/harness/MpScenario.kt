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

    /** Pre-seed frames before the VM mounts (e.g. the subscribe-after-deal case). */
    internal suspend fun preSeed(block: suspend FakeRoomConnectionHandle.() -> Unit): MpScenarioBuilder = apply {
        handle.block()
    }

    suspend fun start(): RunningMpScenario {
        val factory = RemotePokerSessionFactory(
            roomCode = "ABCDEF",
            localUserId = localUserId,
            roomRepository = HarnessRoomRepository(handle),
            telemetry = HarnessTelemetry,
        )
        val vm = PlayPokerViewModel(
            sessionFactory = factory,
            progressionRepository = FakeProgressionRepository(),
            progressionConfig = FakeProgressionConfig(),
            achievementRepository = FakeAchievementRepository(),
            appCache = FakeAppCache(),
            equipmentRepository = FakeEquipmentRepository(),
            inventoryRepository = FakeInventoryRepository(),
            productsRepository = FakeProductsRepository(),
            chipsRepository = FakeChipsRepository(),
            purchaseChipPack = FakePurchaseChipPackUseCase(),
            profileRepository = FakeProfileRepository(),
            reviewPromptCoordinator = FakeReviewPromptCoordinator(),
            dispatcherProvider = dispatchers,
            appScope = AppCoroutineScope(dispatchers),
            clock = Clock.System,
        )
        val recorder = EventRecorder().also { it.attach(scope.backgroundScope, vm) }
        scope.advanceUntilIdle()
        return RunningMpScenario(vm, handle, recorder, scope)
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
    val events: EventRecorder,
    private val scope: TestScope,
) {
    private var seq: Long = 0L

    val table: TableUiState.Active
        get() = vm.state.table as? TableUiState.Active
            ?: error("table is ${vm.state.table::class.simpleName}, not Active yet")

    val connection: ConnectionState get() = vm.state.connection

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

/** Only [connect] is exercised by the MP factory; the rest belong to the lobby. */
private class HarnessRoomRepository(
    private val handle: RoomConnectionHandle,
) : RoomRepository {
    override suspend fun createRoom(maxSeats: Int?, buyIn: Long?): CreateRoomOutcome = error("unused")
    override suspend fun joinRoom(code: String): JoinRoomOutcome = error("unused")
    override suspend fun leaveRoom(code: String): LeaveRoomOutcome = error("unused")
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
    override fun captureUserFeedback(
        message: String,
        isBugReport: Boolean,
        eventId: String?,
        errorCode: Int?,
        email: String?,
    ) = Unit
}

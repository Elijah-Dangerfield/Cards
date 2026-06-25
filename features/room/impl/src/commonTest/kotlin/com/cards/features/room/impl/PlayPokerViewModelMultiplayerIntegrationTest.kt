package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.IntentRejectedException
import com.dangerfield.cards.features.room.impl.session.IntentTimeoutException
import com.dangerfield.cards.features.room.impl.session.LocalBotsSession
import com.dangerfield.cards.features.room.impl.session.RemotePokerSession
import com.dangerfield.cards.features.room.impl.session.RemotePokerSessionFactory

import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **Integration tests for [PlayPokerViewModel] driving a REAL
 * [RemotePokerSessionFactory] + [RemotePokerSession] over a
 * [FakeRoomConnectionHandle].**
 *
 * The bot-mode equivalent ([PlayPokerViewModelIntegrationTest]) pins the
 * VM↔[LocalBotsSession] wiring; this suite is the multiplayer-session
 * mirror it was missing. Server-sent [GameplayFrame]s and connection
 * transitions are pumped into the handle and we assert they propagate all
 * the way through the session → factory projection → VM state, and that
 * player intents leave the VM as the right [ClientFrame] on the wire.
 *
 * What this suite does NOT test:
 *  - Frame→state routing internals — [RemotePokerSessionTest]'s job.
 *  - The pure occupant/table projection in isolation —
 *    [RemotePokerSessionFactoryTest]'s job.
 *  - The real wire contract (bytes over a real socket) — Round 2's
 *    `:integration` module.
 *  - Game mechanics — [com.dangerfield.cards.libraries.gameplay.GameEngineTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayPokerViewModelMultiplayerIntegrationTest : CoroutineTest() {

    @Test
    fun preSnapshot_tableIsLoading_andXpModeIsMultiplayer() = runUnitTest {
        val (vm, _) = buildMpVm()
        advanceUntilIdle()

        assertEquals(TableUiState.Loading, vm.state.table)
        assertEquals(XpMode.MULTIPLAYER, vm.state.xpMode)
        assertTrue(vm.state.occupants.isEmpty())
    }

    @Test
    fun firstSnapshot_marksLocalUserHumanSeat_andDerivesHumanOccupants() = runUnitTest {
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        advanceUntilIdle()

        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 0)))
        advanceUntilIdle()

        val table = assertIs<TableUiState.Active>(vm.state.table)
        assertEquals(0, table.seats.single { it.isHuman }.index)
        assertTrue(table.isHumanTurn, "local user is the acting seat")

        assertEquals(2, vm.state.occupants.size)
        assertTrue(
            vm.state.occupants.all { it is SeatOccupant.Human },
            "both seats are real humans in MP; got ${vm.state.occupants}",
        )
    }

    @Test
    fun serverAdvancesActingSeat_isHumanTurnFlipsOff() = runUnitTest {
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        advanceUntilIdle()

        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 0)))
        advanceUntilIdle()
        assertTrue(assertIs<TableUiState.Active>(vm.state.table).isHumanTurn)

        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 1)))
        advanceUntilIdle()
        assertFalse(
            assertIs<TableUiState.Active>(vm.state.table).isHumanTurn,
            "acting seat moved to the peer; it's no longer the human's turn",
        )
    }

    @Test
    fun submitIntent_sendsSubmitIntentFrameOverWire_andAckCompletes() = runUnitTest {
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        advanceUntilIdle()
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 0)))
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))
        runCurrent()

        val frame = assertIs<ClientFrame.SubmitIntent>(handle.sent.single())
        assertEquals(PlayerIntent.Fold(seatIndex = 0), frame.intent)

        // The server acks the intent the session is waiting on; without
        // this the submit coroutine would sit on its 10s timeout.
        handle.pushFrame(
            GameplayFrame.IntentAck(clientNonce = frame.clientNonce, accepted = true, error = null),
        )
        advanceUntilIdle()
    }

    @Test
    fun submitRejectedByServer_isSwallowed_andVmKeepsProcessing() = runUnitTest {
        // session.submit throws IntentRejectedException on a rejected ack;
        // the VM must catch it (not let it escape the launched coroutine and
        // crash the app) and keep handling subsequent frames.
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        advanceUntilIdle()
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 0)))
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))
        runCurrent()
        val frame = assertIs<ClientFrame.SubmitIntent>(handle.sent.single())
        handle.pushFrame(
            GameplayFrame.IntentAck(clientNonce = frame.clientNonce, accepted = false, error = "not-your-turn"),
        )
        advanceUntilIdle()

        // The VM is still alive: a fresh snapshot still projects through.
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 1)))
        advanceUntilIdle()
        assertFalse(
            assertIs<TableUiState.Active>(vm.state.table).isHumanTurn,
            "VM kept processing snapshots after a rejected submit",
        )
    }

    @Test
    fun submitTimesOut_isSwallowed_andVmKeepsProcessing() = runUnitTest {
        // No ack arrives → session.submit throws IntentTimeoutException after
        // INTENT_TIMEOUT_MS; the VM must swallow it the same way.
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        advanceUntilIdle()
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 0)))
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.Submit(PlayerIntent.Fold(seatIndex = 0)))
        advanceUntilIdle()

        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 1)))
        advanceUntilIdle()
        assertFalse(
            assertIs<TableUiState.Active>(vm.state.table).isHumanTurn,
            "VM kept processing snapshots after a submit timeout",
        )
    }

    @Test
    fun requestNextHand_sendsRequestNextHandFrameOverWire() = runUnitTest {
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        advanceUntilIdle()

        vm.takeAction(PlayPokerAction.RequestNextHand)
        advanceUntilIdle()

        assertIs<ClientFrame.RequestNextHand>(handle.sent.single())
    }

    @Test
    fun connectionTransitions_propagateToVmConnectionState() = runUnitTest {
        val (vm, handle) = buildMpVm()
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Connecting)
        advanceUntilIdle()
        assertEquals(ConnectionState.Reconnecting, vm.state.connection)

        handle.pushConnection(RoomConnection.Connected(sampleRoom()))
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, vm.state.connection)

        handle.pushConnection(RoomConnection.Reconnecting(attempt = 1, cause = null))
        advanceUntilIdle()
        assertEquals(ConnectionState.Reconnecting, vm.state.connection)

        handle.pushConnection(RoomConnection.Closed(ClosedReason.RoomDeleted))
        advanceUntilIdle()
        assertEquals(ConnectionState.Disconnected, vm.state.connection)
    }

    @Test
    fun roomClosedMidSession_emitsRoomClosedEvent() = runUnitTest {
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        val events = mutableListOf<PlayPokerEvent>()
        val collectJob = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 1)))
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Closed(ClosedReason.RoomDeleted))
        advanceUntilIdle()

        assertEquals(
            listOf<PlayPokerEvent>(PlayPokerEvent.RoomClosed(ClosedReason.RoomDeleted)),
            events,
            "a terminal room close must fan out a one-shot exit event",
        )
        collectJob.cancel()
    }

    @Test
    fun userInitiatedClose_doesNotEmitRoomClosedEvent() = runUnitTest {
        // Cancelled = our own teardown when the player leaves; popping again
        // would be a spurious double-navigation.
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        val events = mutableListOf<PlayPokerEvent>()
        val collectJob = launch { vm.eventFlow.collect { events += it } }
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Closed(ClosedReason.Cancelled))
        advanceUntilIdle()

        assertTrue(events.isEmpty(), "self-initiated close must not pop the screen; got $events")
        collectJob.cancel()
    }

    @Test
    fun reconnectBlip_tablePersists_thenResyncsToFreshSnapshot() = runUnitTest {
        val (vm, handle) = buildMpVm(localUserId = LOCAL_USER)
        advanceUntilIdle()

        handle.pushConnection(RoomConnection.Connected(sampleRoom()))
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 1)))
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, vm.state.connection)
        assertFalse(assertIs<TableUiState.Active>(vm.state.table).isHumanTurn, "peer is acting")

        // Network blips — the table state must survive the drop, not blank out.
        handle.pushConnection(RoomConnection.Reconnecting(attempt = 1, cause = null))
        advanceUntilIdle()
        assertEquals(ConnectionState.Reconnecting, vm.state.connection)
        assertIs<TableUiState.Active>(vm.state.table)
        assertFalse(
            assertIs<TableUiState.Active>(vm.state.table).isHumanTurn,
            "state from before the drop persists through Reconnecting",
        )

        // On resume the socket re-syncs with a fresh snapshot — the VM
        // re-converges to it rather than showing the pre-drop state.
        handle.pushConnection(RoomConnection.Connected(sampleRoom()))
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 0)))
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, vm.state.connection)
        assertTrue(
            assertIs<TableUiState.Active>(vm.state.table).isHumanTurn,
            "post-reconnect snapshot resynced — it's now the local user's turn",
        )
    }

    @Test
    fun playScreenMountsAfterDeal_replaysLiveTable_notLoading() = runUnitTest {
        // The recurring MP ordering bug ("stuck on dealing in"): the lobby
        // socket receives the deal's snapshot before the play screen's session
        // subscribes. Tests historically subscribed *before* the action and
        // missed it; this mounts the VM AFTER the snapshot already landed and
        // asserts the table replays — the symmetry-rule guarantee at the VM
        // level, above ReconnectingRoomSocket's own replay test.
        val (vm, _) = startHandBeforeJoinerMounts(actingSeatIndex = 0)
        advanceUntilIdle()

        val table = assertIs<TableUiState.Active>(vm.state.table)
        assertEquals(0, table.seats.single { it.isHuman }.index)
        assertTrue(
            table.isHumanTurn,
            "a play screen mounting after the deal must replay the live table, not sit on Loading",
        )
    }

    @Test
    fun observerNotSeated_tableRendersWithoutHumanSeat() = runUnitTest {
        val (vm, handle) = buildMpVm(localUserId = "observer-not-in-room")
        advanceUntilIdle()

        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = 0)))
        advanceUntilIdle()

        val table = assertIs<TableUiState.Active>(vm.state.table)
        assertTrue(table.seats.none { it.isHuman })
        assertFalse(table.isHumanTurn)
        assertEquals(null, table.humanLegalActions)
    }

    // ---------- helpers ----------

    private fun twoHumanTable(actingSeatIndex: Int) = stubGameState(
        seats = listOf(
            testSeat(index = 0, displayName = "Alice", isBot = false, playerId = LOCAL_USER),
            testSeat(index = 1, displayName = "Bob", isBot = false, playerId = "peer"),
        ),
        actingSeatIndex = actingSeatIndex,
    )

    /**
     * Reusable subscribe-after-action scenario. A hand is already in progress
     * on the socket (its [GameplayFrame.StateSnapshot] has landed) *before* the
     * play screen's [RemotePokerSession] mounts and subscribes — the real
     * ordering where the lobby socket deals the hand, then the player navigates
     * to the play screen. The returned VM is the late mounter; its table must
     * replay the live snapshot rather than sit on [TableUiState.Loading].
     */
    private suspend fun startHandBeforeJoinerMounts(
        localUserId: String = LOCAL_USER,
        actingSeatIndex: Int = 0,
    ): Pair<PlayPokerViewModel, FakeRoomConnectionHandle> {
        val handle = FakeRoomConnectionHandle()
        handle.pushFrame(GameplayFrame.StateSnapshot(twoHumanTable(actingSeatIndex = actingSeatIndex)))
        return buildMpVm(localUserId = localUserId, handle = handle)
    }

    /** No-op [Telemetry] — the factory pushes MP scope tags during bootstrap. */
    private object NoopTelemetry : com.dangerfield.cards.libraries.cards.Telemetry {
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
        ) = Unit
    }

    private fun buildMpVm(
        localUserId: String = LOCAL_USER,
        handle: FakeRoomConnectionHandle = FakeRoomConnectionHandle(),
    ): Pair<PlayPokerViewModel, FakeRoomConnectionHandle> {
        val factory = RemotePokerSessionFactory(
            roomCode = "ABCDEF",
            localUserId = localUserId,
            isPublicTable = false,
            roomRepository = ConnectingRoomRepository(handle),
            telemetry = NoopTelemetry,
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
            chipsRepository = FakeChipsRepository(),
            purchaseChipPack = FakePurchaseChipPackUseCase(),
            profileRepository = FakeProfileRepository(),
            friendRepository = FakeFriendRepository(),
            reviewPromptCoordinator = FakeReviewPromptCoordinator(),
            leaveCashOutNotifier = FakeLeaveCashOutNotifier(),
            dispatcherProvider = dispatchers,
            appScope = com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope(dispatchers),
            clock = kotlin.time.Clock.System,
            socialEnabledConfig = com.dangerfield.cards.libraries.social.SocialEnabled.forTest(enabled = false),
        )
        return vm to handle
    }

    private fun sampleRoom(): Room = Room(
        code = "ABCDEF",
        hostUserId = "host",
        createdAtEpochMs = 0L,
        maxSeats = 6,
        status = RoomStatus.Playing,
        members = emptyList(),
    )

    /**
     * Minimal [RoomRepository] — the multiplayer factory only ever calls
     * [connect] (create/join/leave belong to the lobby). Returns the
     * caller-supplied handle so the test can pump frames into the session.
     */
    private class ConnectingRoomRepository(
        private val handle: RoomConnectionHandle,
    ) : RoomRepository {
        override suspend fun createRoom(maxSeats: Int?, buyIn: Long?, open: Boolean): CreateRoomOutcome = error("unused")
        override suspend fun joinRoom(code: String): JoinRoomOutcome = error("unused")
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = error("unused")
        override suspend fun addBot(code: String, seatIndex: Int?): com.dangerfield.cards.libraries.rooms.AddBotOutcome =
            error("unused")
        override suspend fun removeBot(code: String, botUserId: String): com.dangerfield.cards.libraries.rooms.RemoveBotOutcome =
            error("unused")
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome = error("unused")
        override fun observeActiveRooms(): Flow<List<Room>> = error("unused")
        override fun connect(code: String): RoomConnectionHandle = handle
    }

    private companion object {
        const val LOCAL_USER = "local-user"
    }
}

package com.dangerfield.cards.features.room.impl

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

    private fun buildMpVm(localUserId: String = LOCAL_USER): Pair<PlayPokerViewModel, FakeRoomConnectionHandle> {
        val handle = FakeRoomConnectionHandle()
        val factory = RemotePokerSessionFactory(
            roomCode = "ABCDEF",
            localUserId = localUserId,
            roomRepository = ConnectingRoomRepository(handle),
        )
        val vm = PlayPokerViewModel(
            sessionFactory = factory,
            progressionRepository = FakeProgressionRepository(),
            achievementRepository = FakeAchievementRepository(),
            appCache = FakeAppCache(),
            equipmentRepository = FakeEquipmentRepository(),
            inventoryRepository = FakeInventoryRepository(),
            profileRepository = FakeProfileRepository(),
            reviewPromptCoordinator = FakeReviewPromptCoordinator(),
            dispatcherProvider = dispatchers,
            clock = kotlin.time.Clock.System,
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
        override suspend fun createRoom(maxSeats: Int?): CreateRoomOutcome = error("unused")
        override suspend fun joinRoom(code: String): JoinRoomOutcome = error("unused")
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = error("unused")
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome = error("unused")
        override fun observeActiveRooms(): Flow<List<Room>> = error("unused")
        override fun connect(code: String): RoomConnectionHandle = handle
    }

    private companion object {
        const val LOCAL_USER = "local-user"
    }
}

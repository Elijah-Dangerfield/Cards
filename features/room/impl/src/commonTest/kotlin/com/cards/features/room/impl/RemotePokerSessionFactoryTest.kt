package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.game.SeatOccupant
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins [RemotePokerSessionFactory]'s projection logic — the pure
 * mappings from a server-sent [com.dangerfield.cards.libraries.gameplay.GameState]
 * into the occupant + table shapes the play screen consumes. This is the
 * load-bearing seam for multiplayer: the local human's seat is derived
 * by matching `localUserId` against each seat's `playerId`, and every
 * action-submission path keys off that derivation.
 *
 * Covered here:
 *  - [RemotePokerSessionFactory.occupantsFor] derives Human / Bot / Empty
 *    occupants from seat shape, and carries the human's id + name.
 *  - [RemotePokerSessionFactory.tableFor] renders Loading pre-snapshot,
 *    marks the local user's seat human by id lookup at any index, and
 *    survives the observer case (local user not seated) without crashing.
 *  - The MP labels ([RemotePokerSessionFactory.difficultyName] /
 *    [RemotePokerSessionFactory.xpMode]) are pinned.
 *  - [RemotePokerSessionFactory.create] opens the room connection.
 *
 * NOT covered here: the frame→state routing inside [RemotePokerSession]
 * (pinned in `RemotePokerSessionTest`) and the wire contract (Round 2's
 * integration module).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemotePokerSessionFactoryTest : CoroutineTest() {

    @Test
    fun occupantsFor_emptyState_returnsEmptyList() = runUnitTest {
        val occupants = factory().occupantsFor(stubGameState(seats = emptyList()))
        assertEquals(emptyList(), occupants)
    }

    @Test
    fun occupantsFor_filledSeats_derivesHumanBotEmpty() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, displayName = "Alice", isBot = false, playerId = "alice"),
                testSeat(index = 1, displayName = "Robo", isBot = true, playerId = "bot-1"),
                testSeat(index = 2, playerId = null),
            ),
        )

        val occupants = factory().occupantsFor(state)

        assertEquals(3, occupants.size)
        val human = assertIs<SeatOccupant.Human>(occupants[0])
        assertEquals(0, human.seatIndex)
        val bot = assertIs<SeatOccupant.Bot>(occupants[1])
        assertEquals(1, bot.seatIndex)
        val empty = assertIs<SeatOccupant.Empty>(occupants[2])
        assertEquals(2, empty.seatIndex)
    }

    @Test
    fun occupantsFor_humanSeat_carriesUserIdAndDisplayName() = runUnitTest {
        val state = stubGameState(
            seats = listOf(testSeat(index = 0, displayName = "Alice", isBot = false, playerId = "alice")),
        )

        val human = assertIs<SeatOccupant.Human>(factory().occupantsFor(state).single())

        assertEquals("alice", human.userId)
        assertEquals("Alice", human.displayName)
        assertEquals(0, human.seatIndex)
    }

    @Test
    fun tableFor_emptyState_returnsLoading() = runUnitTest {
        val table = factory().tableFor(
            state = stubGameState(seats = emptyList()),
            lastWinners = null,
            lastActionBySeat = emptyMap(),
            humanProfile = null,
            humanLevel = null,
        )
        assertEquals(TableUiState.Loading, table)
    }

    @Test
    fun tableFor_localUserAtSeat0_marksSeat0Human_andIsHumanTurn() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "local-user"),
                testSeat(index = 1, playerId = "peer"),
            ),
            actingSeatIndex = 0,
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user"))

        assertEquals(0, table.seats.single { it.isHuman }.index)
        assertTrue(table.isHumanTurn)
    }

    @Test
    fun tableFor_localUserAtSeat3_marksSeat3Human() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "peer-0"),
                testSeat(index = 1, playerId = "peer-1"),
                testSeat(index = 2, playerId = "peer-2"),
                testSeat(index = 3, playerId = "local-user"),
            ),
            actingSeatIndex = 1,
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user"))

        assertEquals(3, table.seats.single { it.isHuman }.index)
        assertFalse(table.isHumanTurn)
    }

    @Test
    fun tableFor_localUserNotSeated_hasNoHumanSeat_andDoesNotCrash() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "peer-0"),
                testSeat(index = 1, playerId = "peer-1"),
            ),
            actingSeatIndex = 0,
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "observer-not-in-room"))

        assertTrue(table.seats.none { it.isHuman })
        assertFalse(table.isHumanTurn)
        assertEquals(null, table.humanLegalActions)
    }

    @Test
    fun tableFor_userReseats_pickedUpInNextProjection() = runUnitTest {
        val before = assertIs<TableUiState.Active>(
            tableFor(
                state = stubGameState(
                    seats = listOf(
                        testSeat(index = 0, playerId = "local-user"),
                        testSeat(index = 1, playerId = "peer"),
                    ),
                ),
                localUserId = "local-user",
            ),
        )
        assertEquals(0, before.seats.single { it.isHuman }.index)

        val after = assertIs<TableUiState.Active>(
            tableFor(
                state = stubGameState(
                    seats = listOf(
                        testSeat(index = 0, playerId = "peer"),
                        testSeat(index = 1, playerId = "local-user"),
                    ),
                ),
                localUserId = "local-user",
            ),
        )
        assertEquals(1, after.seats.single { it.isHuman }.index)
    }

    @Test
    fun bootstrap_drivesSession_routingSnapshotFramesIntoState() = runUnitTest {
        val handle = FakeRoomConnectionHandle()
        val factory = factory(rooms = FactoryRoomRepository(handle = handle))
        val session = factory.create(
            humanSeatIndex = 0,
            botSpeedProvider = { error("bot speed is unused for remote sessions") },
            onHandEnded = { _, _, _ -> },
        )

        val runJob = launch { factory.bootstrap(session) }
        advanceUntilIdle()

        val snapshot = stubGameState(seats = listOf(testSeat(index = 0, playerId = "local-user")))
        handle.pushFrame(GameplayFrame.StateSnapshot(snapshot))
        advanceUntilIdle()

        assertEquals(snapshot, session.gameStateFlow.value)
        runJob.cancel()
    }

    @Test
    fun tableFor_botStackedTable_marksPracticeTier() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, isBot = false, playerId = "local-user"),
                testSeat(index = 1, isBot = false, playerId = "peer"),
                testSeat(index = 2, isBot = true, playerId = "bot-1"),
                testSeat(index = 3, isBot = true, playerId = "bot-2"),
                testSeat(index = 4, isBot = true, playerId = "bot-3"),
            ),
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user"))

        assertTrue(table.practiceTierBotsPresent)
    }

    @Test
    fun tableFor_majorityHumanTable_noPracticeTier() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, isBot = false, playerId = "local-user"),
                testSeat(index = 1, isBot = false, playerId = "peer"),
                testSeat(index = 2, isBot = true, playerId = "bot-1"),
                testSeat(index = 3, isBot = true, playerId = "bot-2"),
            ),
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user"))

        assertFalse(table.practiceTierBotsPresent)
    }

    @Test
    fun difficultyName_and_xpMode_areMultiplayer() = runUnitTest {
        val factory = factory()
        assertEquals("Multiplayer", factory.difficultyName)
        assertEquals(XpMode.MULTIPLAYER, factory.xpMode)
    }

    @Test
    fun create_opensRoomConnection_andReturnsSession() = runUnitTest {
        val rooms = FactoryRoomRepository()
        val session = factory(rooms = rooms).create(
            humanSeatIndex = 0,
            botSpeedProvider = { error("bot speed is unused for remote sessions") },
            onHandEnded = { _, _, _ -> },
        )

        assertEquals(1, rooms.connectCalls)
        assertIs<RemotePokerSession>(session)
    }

    // ---------- scaffolding ----------

    private fun factory(
        localUserId: String = "local-user",
        rooms: RoomRepository = FactoryRoomRepository(),
    ): RemotePokerSessionFactory = RemotePokerSessionFactory(
        roomCode = "ABCDEF",
        localUserId = localUserId,
        roomRepository = rooms,
    )

    private fun tableFor(
        state: com.dangerfield.cards.libraries.gameplay.GameState,
        localUserId: String,
    ): TableUiState = factory(localUserId = localUserId).tableFor(
        state = state,
        lastWinners = null,
        lastActionBySeat = emptyMap(),
        humanProfile = null,
        humanLevel = null,
    )

    /**
     * Minimal [RoomRepository] — only [connect] is exercised by the
     * factory (the lobby owns create/join/leave). Counts connect calls
     * so the wiring test can assert the handle is opened exactly once.
     */
    private class FactoryRoomRepository(
        private val handle: RoomConnectionHandle = FakeRoomConnectionHandle(),
    ) : RoomRepository {
        var connectCalls: Int = 0
            private set

        override suspend fun createRoom(maxSeats: Int?): CreateRoomOutcome = error("unused")
        override suspend fun joinRoom(code: String): JoinRoomOutcome = error("unused")
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = error("unused")
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome = error("unused")
        override fun observeActiveRooms(): Flow<List<Room>> = error("unused")
        override fun connect(code: String): RoomConnectionHandle {
            connectCalls += 1
            return handle
        }
    }
}

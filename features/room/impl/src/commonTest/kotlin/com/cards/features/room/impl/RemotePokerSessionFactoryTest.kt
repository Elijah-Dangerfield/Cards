package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.session.RemotePokerSession
import com.dangerfield.cards.features.room.impl.session.RemotePokerSessionFactory

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
    fun botsOnlyTable_isSubsidizedOnPublic_butPracticeOnPrivate() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, isBot = false, playerId = "local-user"), // lone human
                testSeat(index = 1, isBot = true, playerId = "bot-1"),        // + disclosed bot
            ),
        )

        val publicTable = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user", isPublic = true))
        assertTrue(publicTable.subsidizedBotTable, "public lone-human-vs-bots = disclosed-bot subsidy")

        val privateTable = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user", isPublic = false))
        assertFalse(privateTable.subsidizedBotTable, "a private bots-only game stays practice")
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
    fun occupantsFor_humanSeat_derivesLevelFromServerSnapshottedXp() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, displayName = "Alice", isBot = false, playerId = "alice", xp = 2_500),
            ),
        )

        val human = assertIs<SeatOccupant.Human>(factory().occupantsFor(state).single())

        assertEquals(
            com.dangerfield.cards.libraries.cards.levelProgressFor(2_500).level,
            human.level,
        )
    }

    @Test
    fun occupantsFor_humanSeat_nullXp_levelZero() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, displayName = "Alice", isBot = false, playerId = "alice", xp = null),
            ),
        )

        val human = assertIs<SeatOccupant.Human>(factory().occupantsFor(state).single())

        assertEquals(0, human.level)
    }

    @Test
    fun tableFor_remoteOpponent_rendersLevelBadgeFromXp() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "local-user", xp = null),
                testSeat(index = 1, playerId = "peer", isBot = false, xp = 2_500),
            ),
            actingSeatIndex = 0,
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user"))

        val opponentBadge = table.seats.single { it.index == 1 }.seatBadge
        assertEquals(
            SeatBadge.Level(com.dangerfield.cards.libraries.cards.levelProgressFor(2_500).level),
            opponentBadge,
        )
    }

    @Test
    fun occupantsFor_humanSeat_derivesLevelThroughConfiguredCurve() = runUnitTest {
        // A steep server-tuned curve (10k XP per early level) keeps 2,500 XP at
        // level 1 where the bundled quadratic curve shows level 4 — proving the
        // opponent level runs through the threaded curve, not the bundled default.
        val steepCurve = com.dangerfield.cards.libraries.cards.LevelCurve(baseXp = 10_000)
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, displayName = "Alice", isBot = false, playerId = "alice", xp = 2_500),
            ),
        )

        val human = assertIs<SeatOccupant.Human>(factory().occupantsFor(state, steepCurve).single())

        assertEquals(1, human.level)
        assertEquals(4, com.dangerfield.cards.libraries.cards.levelProgressFor(2_500).level)
    }

    @Test
    fun tableFor_remoteOpponent_levelBadgeRunsThroughConfiguredCurve() = runUnitTest {
        val steepCurve = com.dangerfield.cards.libraries.cards.LevelCurve(baseXp = 10_000)
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "local-user", xp = null),
                testSeat(index = 1, playerId = "peer", isBot = false, xp = 2_500),
            ),
            actingSeatIndex = 0,
        )

        val table = assertIs<TableUiState.Active>(
            tableFor(state, localUserId = "local-user", curve = steepCurve),
        )

        assertEquals(
            SeatBadge.Level(1),
            table.seats.single { it.index == 1 }.seatBadge,
        )
    }

    @Test
    fun tableFor_remoteOpponent_nullXp_omitsBadge() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "local-user", xp = null),
                testSeat(index = 1, playerId = "peer", isBot = false, xp = null),
            ),
            actingSeatIndex = 0,
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user"))

        assertEquals(null, table.seats.single { it.index == 1 }.seatBadge)
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
    fun humanSeatIndex_matchesLocalUserAtAnyIndex() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "peer-0"),
                testSeat(index = 1, playerId = "peer-1"),
                testSeat(index = 2, playerId = "local-user"),
            ),
        )

        assertEquals(2, factory(localUserId = "local-user").humanSeatIndex(state))
    }

    @Test
    fun humanSeatIndex_localUserNotSeated_returnsNegativeOne() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "peer-0"),
                testSeat(index = 1, playerId = "peer-1"),
            ),
        )

        assertEquals(-1, factory(localUserId = "observer-not-in-room").humanSeatIndex(state))
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
        assertTrue(table.waitingToBeDealtIn, "a seatless local member is waiting to be dealt in")
    }

    @Test
    fun tableFor_localUserSeated_isNotWaitingToBeDealtIn() = runUnitTest {
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, playerId = "local-user"),
                testSeat(index = 1, playerId = "peer"),
            ),
            actingSeatIndex = 0,
        )

        val table = assertIs<TableUiState.Active>(tableFor(state, localUserId = "local-user"))

        assertFalse(table.waitingToBeDealtIn)
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
        isPublicTable: Boolean = false,
    ): RemotePokerSessionFactory = RemotePokerSessionFactory(
        roomCode = "ABCDEF",
        localUserId = localUserId,
        isPublicTable = isPublicTable,
        roomRepository = rooms,
        telemetry = NoopTelemetry,
    )

    /** No-op [Telemetry] — the factory only calls setRoom during bootstrap. */
    private object NoopTelemetry : com.dangerfield.cards.libraries.cards.Telemetry {
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

    private fun tableFor(
        state: com.dangerfield.cards.libraries.gameplay.GameState,
        localUserId: String,
        isPublic: Boolean = false,
        curve: com.dangerfield.cards.libraries.cards.LevelCurve =
            com.dangerfield.cards.libraries.cards.DefaultLevelCurve,
    ): TableUiState = factory(localUserId = localUserId, isPublicTable = isPublic).tableFor(
        state = state,
        lastWinners = null,
        lastActionBySeat = emptyMap(),
        humanProfile = null,
        humanLevel = null,
        curve = curve,
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

        override suspend fun createRoom(maxSeats: Int?, buyIn: Long?, open: Boolean): CreateRoomOutcome = error("unused")
        override suspend fun joinRoom(code: String): JoinRoomOutcome = error("unused")
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = error("unused")
        override suspend fun addBot(code: String, seatIndex: Int?): com.dangerfield.cards.libraries.rooms.AddBotOutcome =
            error("unused")
        override suspend fun removeBot(code: String, botUserId: String): com.dangerfield.cards.libraries.rooms.RemoveBotOutcome =
            error("unused")
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome = error("unused")
        override fun observeActiveRooms(): Flow<List<Room>> = error("unused")
        override fun connect(code: String): RoomConnectionHandle {
            connectCalls += 1
            return handle
        }
    }
}

package com.dangerfield.cards.features.rooms.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.CandidatesOutcome
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.FindTableOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.MatchmakingRepository
import com.dangerfield.cards.libraries.rooms.PlayBotsOutcome
import com.dangerfield.cards.libraries.rooms.RemoveBotOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [PublicSearchingViewModel]'s honest-matchmaking state machine: find →
 * watch for humans → hand off when a hand deals, or offer disclosed bots if the
 * long window elapses alone. Heavy use of in-memory fakes so the assertions stay
 * on the VM's branching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicSearchingViewModelTest : CoroutineTest() {

    @Test
    fun onStart_findsTable_andStaysSearching() = runUnitTest {
        val mm = FakeMatchmakingRepository(find = FindTableOutcome.Success(roomOf(), created = true))
        val vm = buildVm(matchmaking = mm)
        runCurrent()

        assertEquals(1, mm.candidatesCalls, "the browse runs first")
        assertEquals(1, mm.findCalls, "no candidates → fall through to the genuine wait")
        assertEquals(SearchPhase.Searching, vm.state.phase)
        assertNull(vm.state.error)
    }

    @Test
    fun onStart_withCandidates_showsChooser_withoutSeating() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            candidates = CandidatesOutcome.Success(
                listOf(
                    roomOf(code = "AAA111", members = listOf(member("h1", "H1", isConnected = true))),
                    roomOf(code = "BBB222", members = emptyList()),
                ),
            ),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()

        assertEquals(SearchPhase.Choosing, vm.state.phase)
        assertEquals(listOf("AAA111", "BBB222"), vm.state.candidates.map { it.code })
        assertEquals(0, mm.findCalls, "the chooser seats no one — find is not called")

        // Entering the chooser arms the live candidates re-poll (an infinite
        // delay loop). Tear it down so the test's virtual clock can drain
        // instead of the eager dispatcher busy-spinning the poll forever.
        vm.takeAction(PublicSearchingAction.Cancel)
        runCurrent()
    }

    @Test
    fun joinCandidate_joinsByCode_andHandsOffWhenDealing() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val mm = FakeMatchmakingRepository(
            candidates = CandidatesOutcome.Success(listOf(roomOf(code = "AAA111"))),
        )
        val rooms = FakeRoomRepository(
            connection = conn,
            join = JoinRoomOutcome.Success(roomOf(code = "AAA111"), alreadyJoined = false),
        )
        val vm = buildVm(matchmaking = mm, rooms = rooms)
        runCurrent()
        assertEquals(SearchPhase.Choosing, vm.state.phase)

        vm.eventFlow.test {
            vm.takeAction(PublicSearchingAction.JoinCandidate("AAA111"))
            runCurrent()
            assertEquals(listOf("AAA111"), rooms.joinedCodes, "picking a table joins it by code")

            conn.emit(
                RoomConnection.Connected(
                    roomOf(
                        code = "AAA111",
                        status = RoomStatus.Playing,
                        members = listOf(
                            member(LOCAL_USER, "You", isConnected = true),
                            member("peer", "Peer", isConnected = true, seatIndex = 1),
                        ),
                    ),
                ),
            )
            val event = assertIs<PublicSearchingEvent.NavigateToTable>(awaitItem())
            assertEquals("AAA111", event.roomCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun joinCandidate_overBalance_surfacesInsufficientError() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            candidates = CandidatesOutcome.Success(listOf(roomOf(code = "AAA111"))),
        )
        val rooms = FakeRoomRepository(join = JoinRoomOutcome.OverBalance("too rich"))
        val vm = buildVm(matchmaking = mm, rooms = rooms)
        runCurrent()

        vm.takeAction(PublicSearchingAction.JoinCandidate("AAA111"))
        runCurrent()

        assertEquals(SearchError.InsufficientBalance, vm.state.error)
    }

    @Test
    fun startFreshTable_fromChooser_fallsThroughToTheGenuineWait() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            find = FindTableOutcome.Success(roomOf(), created = true),
            candidates = CandidatesOutcome.Success(listOf(roomOf(code = "AAA111"))),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        assertEquals(SearchPhase.Choosing, vm.state.phase)

        vm.takeAction(PublicSearchingAction.StartFreshTable)
        runCurrent()

        assertEquals(SearchPhase.Searching, vm.state.phase)
        assertEquals(1, mm.findCalls, "starting fresh opens a real table to wait in")
    }

    @Test
    fun chooser_rePollsCandidates_andRefreshesTheVisibleList() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            candidatesSequence = listOf(
                CandidatesOutcome.Success(listOf(roomOf(code = "AAA111"))),
                CandidatesOutcome.Success(listOf(roomOf(code = "AAA111"), roomOf(code = "BBB222"))),
            ),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        assertEquals(SearchPhase.Choosing, vm.state.phase)
        assertEquals(listOf("AAA111"), vm.state.candidates.map { it.code })

        // A re-poll fires on the interval and surfaces the newly-formed table.
        testScheduler.advanceTimeBy(6.seconds)
        testScheduler.runCurrent()

        assertEquals(SearchPhase.Choosing, vm.state.phase)
        assertEquals(listOf("AAA111", "BBB222"), vm.state.candidates.map { it.code })

        // Tear the live poll down so the test's virtual clock can drain.
        vm.takeAction(PublicSearchingAction.Cancel)
        runCurrent()
    }

    @Test
    fun chooser_rePollComesBackEmpty_fallsThroughToTheGenuineWait() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            find = FindTableOutcome.Success(roomOf(), created = true),
            candidatesSequence = listOf(
                CandidatesOutcome.Success(listOf(roomOf(code = "AAA111"))),
                CandidatesOutcome.Success(emptyList()),
            ),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        assertEquals(SearchPhase.Choosing, vm.state.phase)

        // Every table emptied out while deciding — the re-poll drops the dead
        // chooser and converges on the genuine find-and-wait search.
        testScheduler.advanceTimeBy(6.seconds)
        testScheduler.runCurrent()

        assertEquals(SearchPhase.Searching, vm.state.phase)
        assertEquals(emptyList(), vm.state.candidates)
        assertEquals(1, mm.findCalls, "the empty re-poll opens a real table to wait in")

        // Tear the live search down so the test's virtual clock can drain.
        vm.takeAction(PublicSearchingAction.Cancel)
        runCurrent()
    }

    @Test
    fun chooser_transientRePollFailure_keepsTheExistingList() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            candidatesSequence = listOf(
                CandidatesOutcome.Success(listOf(roomOf(code = "AAA111"))),
                CandidatesOutcome.NetworkError(RuntimeException("blip")),
            ),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        assertEquals(listOf("AAA111"), vm.state.candidates.map { it.code })

        // A flaky re-poll leaves the chooser untouched rather than yanking it
        // out from under the user.
        testScheduler.advanceTimeBy(6.seconds)
        testScheduler.runCurrent()

        assertEquals(SearchPhase.Choosing, vm.state.phase)
        assertEquals(listOf("AAA111"), vm.state.candidates.map { it.code })
        assertNull(vm.state.error)

        // Tear the live poll down so the test's virtual clock can drain.
        vm.takeAction(PublicSearchingAction.Cancel)
        runCurrent()
    }

    @Test
    fun connected_countsOtherConnectedHumans_excludingSelfAndBots() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val vm = buildVm(rooms = FakeRoomRepository(connection = conn))
        runCurrent()

        conn.emit(
            RoomConnection.Connected(
                roomOf(
                    members = listOf(
                        member(LOCAL_USER, "You", isConnected = true),
                        member("peer", "Peer", isConnected = true, seatIndex = 1),
                        member("bot-1", "Robo", isConnected = true, seatIndex = 2, isBot = true),
                        member("ghost", "Ghost", isConnected = false, seatIndex = 3),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(1, vm.state.realPlayersFound, "only the one other *connected human* counts")
    }

    @Test
    fun tableStartsPlaying_emitsNavigateToTable() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val vm = buildVm(rooms = FakeRoomRepository(connection = conn))
        runCurrent()

        vm.eventFlow.test {
            conn.emit(
                RoomConnection.Connected(
                    roomOf(
                        status = RoomStatus.Playing,
                        members = listOf(
                            member(LOCAL_USER, "You", isConnected = true),
                            member("peer", "Peer", isConnected = true, seatIndex = 1),
                        ),
                    ),
                ),
            )
            val event = assertIs<PublicSearchingEvent.NavigateToTable>(awaitItem())
            assertEquals("ABC123", event.roomCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun searchWindowElapsesAlone_offersDisclosedBots() = runUnitTest {
        val vm = buildVm()
        runCurrent()
        assertEquals(SearchPhase.Searching, vm.state.phase)

        testScheduler.advanceTimeBy(61.seconds)
        testScheduler.runCurrent()

        assertEquals(SearchPhase.BotFallbackOffer, vm.state.phase, "alone at the window → honest bot offer")
    }

    @Test
    fun searchWindowElapses_withAHumanPresent_doesNotOfferBots() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val vm = buildVm(rooms = FakeRoomRepository(connection = conn))
        runCurrent()
        conn.emit(
            RoomConnection.Connected(
                roomOf(
                    members = listOf(
                        member(LOCAL_USER, "You", isConnected = true),
                        member("peer", "Peer", isConnected = true, seatIndex = 1),
                    ),
                ),
            ),
        )
        runCurrent()

        testScheduler.advanceTimeBy(61.seconds)
        testScheduler.runCurrent()

        assertEquals(SearchPhase.Searching, vm.state.phase, "a human is here — never fall back to bots")
    }

    @Test
    fun playBots_success_movesToJoiningBots_andCallsPlayBots() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            find = FindTableOutcome.Success(roomOf(), created = true),
            playBots = PlayBotsOutcome.Success(roomOf()),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        vm.takeAction(PublicSearchingAction.PlayBots)
        runCurrent()

        assertEquals(1, mm.playBotsCalls)
        assertEquals(SearchPhase.JoiningBots, vm.state.phase)
    }

    @Test
    fun playBots_whenARealPlayerJoined_returnsToSearching() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            find = FindTableOutcome.Success(roomOf(), created = true),
            playBots = PlayBotsOutcome.RealPlayerJoined,
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        vm.takeAction(PublicSearchingAction.PlayBots)
        runCurrent()

        assertEquals(SearchPhase.Searching, vm.state.phase, "a real player beat the bots — keep the real game")
    }

    @Test
    fun findFailure_network_surfacesError() = runUnitTest {
        val mm = FakeMatchmakingRepository(find = FindTableOutcome.NetworkError(RuntimeException("boom")))
        val vm = buildVm(matchmaking = mm)
        runCurrent()

        assertEquals(SearchError.Network, vm.state.error)
    }

    @Test
    fun roomDeletedUnderUs_silentlyReFinds() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val mm = FakeMatchmakingRepository(find = FindTableOutcome.Success(roomOf(), created = true))
        val vm = buildVm(matchmaking = mm, rooms = FakeRoomRepository(connection = conn))
        runCurrent()
        assertEquals(1, mm.findCalls)

        // The table is GC'd (e.g. a server restart) — the code is dead.
        conn.emit(RoomConnection.Closed(ClosedReason.RoomDeleted))
        runCurrent()
        // The re-find is backed off so a flapping server can't spin a tight loop.
        testScheduler.advanceTimeBy(3.seconds)
        testScheduler.runCurrent()

        assertEquals(2, mm.findCalls, "a vanished table triggers a fresh find, not a dead reconnect")
    }

    @Test
    fun repeatedlyVanishingTable_stopsReFinding_andShowsAnError() = runUnitTest {
        // A server that keeps handing back dead tables must not loop forever.
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val mm = FakeMatchmakingRepository(find = FindTableOutcome.Success(roomOf(), created = true))
        val vm = buildVm(matchmaking = mm, rooms = FakeRoomRepository(connection = conn))
        runCurrent()

        // Each Closed triggers one backed-off re-find, up to the cap.
        repeat(5) {
            conn.emit(RoomConnection.Closed(ClosedReason.RoomDeleted))
            runCurrent()
            testScheduler.advanceTimeBy(3.seconds)
            testScheduler.runCurrent()
        }

        // 1 initial find + the 3-attempt re-find cap = 4, then it gives up.
        assertEquals(4, mm.findCalls, "re-finding is capped, not infinite")
        assertEquals(SearchError.Network, vm.state.error)
    }

    @Test
    fun playBots_roomNotFound_reFinds() = runUnitTest {
        // The table was reclaimed before we could fill it — find a fresh one.
        val mm = FakeMatchmakingRepository(
            find = FindTableOutcome.Success(roomOf(), created = true),
            playBots = PlayBotsOutcome.RoomNotFound,
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        vm.takeAction(PublicSearchingAction.PlayBots)
        runCurrent()

        assertEquals(2, mm.findCalls, "a gone table on play-bots triggers a re-find")
    }

    @Test
    fun playBots_networkError_returnsToOffer_withError() = runUnitTest {
        val mm = FakeMatchmakingRepository(
            find = FindTableOutcome.Success(roomOf(), created = true),
            playBots = PlayBotsOutcome.NetworkError(RuntimeException("boom")),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        vm.takeAction(PublicSearchingAction.PlayBots)
        runCurrent()

        assertEquals(SearchPhase.BotFallbackOffer, vm.state.phase, "a failed play-bots returns to the offer")
        assertEquals(SearchError.Network, vm.state.error)
    }

    @Test
    fun tryAgainLater_leavesTheSeat_andNavigatesBack() = runUnitTest {
        val rooms = FakeRoomRepository()
        val vm = buildVm(rooms = rooms)
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(PublicSearchingAction.TryAgainLater)
            assertIs<PublicSearchingEvent.NavigateBack>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        runCurrent()
        assertEquals(listOf("ABC123"), rooms.leftCodes, "bowing out releases the seat")
    }

    @Test
    fun transientReconnect_isBenign_keepsSearching() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val vm = buildVm(rooms = FakeRoomRepository(connection = conn))
        runCurrent()

        conn.emit(RoomConnection.Reconnecting(attempt = 2, cause = null))
        runCurrent()

        assertEquals(SearchPhase.Searching, vm.state.phase, "a transient blip doesn't error or bail")
        assertNull(vm.state.error)
    }

    @Test
    fun rejectedSocket_isTerminal_doesNotReFind() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val mm = FakeMatchmakingRepository(find = FindTableOutcome.Success(roomOf(), created = true))
        val vm = buildVm(matchmaking = mm, rooms = FakeRoomRepository(connection = conn))
        runCurrent()

        // Rejected (not a member / unknown code) is terminal — chasing it with a
        // re-find would just loop. Surface an error instead.
        conn.emit(RoomConnection.Closed(ClosedReason.Rejected))
        runCurrent()

        assertEquals(SearchError.Connection, vm.state.error)
        assertEquals(1, mm.findCalls, "a rejected socket is terminal, not re-found")
    }

    @Test
    fun searchTimedOut_whileJoiningBots_isIgnored() = runUnitTest {
        // A stray late timer must not re-offer bots once the player already
        // accepted them.
        val mm = FakeMatchmakingRepository(
            find = FindTableOutcome.Success(roomOf(), created = true),
            playBots = PlayBotsOutcome.Success(roomOf()),
        )
        val vm = buildVm(matchmaking = mm)
        runCurrent()
        vm.takeAction(PublicSearchingAction.PlayBots)
        runCurrent()
        assertEquals(SearchPhase.JoiningBots, vm.state.phase)

        vm.takeAction(PublicSearchingAction.SearchTimedOut)
        runCurrent()
        assertEquals(SearchPhase.JoiningBots, vm.state.phase, "the offer guard holds outside the Searching phase")
    }

    @Test
    fun peerDisconnects_dropsTheFoundCount_backToAlone() = runUnitTest {
        val conn = MutableSharedFlow<RoomConnection>(extraBufferCapacity = 8)
        val vm = buildVm(rooms = FakeRoomRepository(connection = conn))
        runCurrent()
        conn.emit(
            RoomConnection.Connected(
                roomOf(
                    members = listOf(
                        member(LOCAL_USER, "You", isConnected = true),
                        member("peer", "Peer", isConnected = true, seatIndex = 1),
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals(1, vm.state.realPlayersFound)

        // The peer's socket drops — they no longer count as present.
        conn.emit(
            RoomConnection.Connected(
                roomOf(
                    members = listOf(
                        member(LOCAL_USER, "You", isConnected = true),
                        member("peer", "Peer", isConnected = false, seatIndex = 1),
                    ),
                ),
            ),
        )
        runCurrent()
        assertEquals(0, vm.state.realPlayersFound, "a disconnected peer isn't counted as a found player")
    }

    @Test
    fun cancel_leavesTheSeat_andNavigatesBack() = runUnitTest {
        val rooms = FakeRoomRepository()
        val vm = buildVm(rooms = rooms)
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(PublicSearchingAction.Cancel)
            assertIs<PublicSearchingEvent.NavigateBack>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        runCurrent()
        assertEquals(listOf("ABC123"), rooms.leftCodes, "cancelling releases the held seat")
    }

    @Test
    fun keepWaiting_returnsToSearching_andReArmsTheWindow() = runUnitTest {
        val vm = buildVm()
        runCurrent()
        testScheduler.advanceTimeBy(61.seconds)
        testScheduler.runCurrent()
        assertEquals(SearchPhase.BotFallbackOffer, vm.state.phase)

        vm.takeAction(PublicSearchingAction.KeepWaiting)
        runCurrent()
        assertEquals(SearchPhase.Searching, vm.state.phase)

        // The re-armed window elapses again → the offer comes back.
        testScheduler.advanceTimeBy(61.seconds)
        testScheduler.runCurrent()
        assertEquals(SearchPhase.BotFallbackOffer, vm.state.phase)
    }

    // ---------- scaffolding ----------

    private fun buildVm(
        matchmaking: MatchmakingRepository = FakeMatchmakingRepository(),
        rooms: RoomRepository = FakeRoomRepository(),
        auth: AuthRepository = AlwaysSignedInAuth(),
        minBuyIn: Long = 1_000,
        maxBuyIn: Long = 5_000,
    ): PublicSearchingViewModel = PublicSearchingViewModel(
        minBuyIn = minBuyIn,
        maxBuyIn = maxBuyIn,
        matchmaking = matchmaking,
        rooms = rooms,
        auth = auth,
        appScope = AppCoroutineScope(dispatchers),
    )

    private val LOCAL_USER = "11111111-1111-1111-1111-111111111111"

    private fun member(
        userId: String,
        displayName: String,
        isConnected: Boolean,
        seatIndex: Int = 0,
        isBot: Boolean = false,
    ) = RoomMember(
        userId = userId,
        displayName = displayName,
        seatIndex = seatIndex,
        joinedAtEpochMs = 1_700_000_000_000,
        isConnected = isConnected,
        isBot = isBot,
    )

    private fun roomOf(
        code: String = "ABC123",
        status: RoomStatus = RoomStatus.Lobby,
        members: List<RoomMember> = listOf(member("11111111-1111-1111-1111-111111111111", "You", isConnected = true)),
    ) = Room(
        code = code,
        hostUserId = "00000000-0000-0000-0000-000000000000",
        createdAtEpochMs = 1_700_000_000_000,
        maxSeats = 6,
        status = status,
        members = members,
    )

    private class FakeMatchmakingRepository(
        private val find: FindTableOutcome = FindTableOutcome.Success(
            Room("ABC123", "00000000-0000-0000-0000-000000000000", 0, 6, RoomStatus.Lobby, emptyList()),
            created = true,
        ),
        private val playBots: PlayBotsOutcome = PlayBotsOutcome.Success(
            Room("ABC123", "00000000-0000-0000-0000-000000000000", 0, 6, RoomStatus.Lobby, emptyList()),
        ),
        // Default to no candidates so the VM falls through to the genuine
        // find-and-wait path — keeps the legacy assertions on findTable valid.
        private val candidates: CandidatesOutcome = CandidatesOutcome.Success(emptyList()),
        // Successive findCandidates calls walk this list (the initial browse +
        // each re-poll); once exhausted the last entry repeats. Lets a test
        // drive the chooser's live re-poll without a custom fake.
        private val candidatesSequence: List<CandidatesOutcome> = emptyList(),
    ) : MatchmakingRepository {
        var findCalls: Int = 0
            private set
        var playBotsCalls: Int = 0
            private set
        var candidatesCalls: Int = 0
            private set

        override suspend fun findTable(minBuyIn: Long, maxBuyIn: Long): FindTableOutcome {
            findCalls += 1
            return find
        }

        override suspend fun findCandidates(minBuyIn: Long, maxBuyIn: Long): CandidatesOutcome {
            val outcome = candidatesSequence.getOrNull(candidatesCalls)
                ?: candidatesSequence.lastOrNull()
                ?: candidates
            candidatesCalls += 1
            return outcome
        }

        override suspend fun playBots(code: String): PlayBotsOutcome {
            playBotsCalls += 1
            return playBots
        }
    }

    private class FakeRoomRepository(
        private val connection: Flow<RoomConnection> = flow { },
        private val join: JoinRoomOutcome = JoinRoomOutcome.Success(
            Room("ABC123", "00000000-0000-0000-0000-000000000000", 0, 6, RoomStatus.Lobby, emptyList()),
            alreadyJoined = false,
        ),
    ) : RoomRepository {
        val leftCodes: MutableList<String> = mutableListOf()
        val joinedCodes: MutableList<String> = mutableListOf()

        override fun connect(code: String): RoomConnectionHandle = object : RoomConnectionHandle {
            override val connection: Flow<RoomConnection> = this@FakeRoomRepository.connection
            override val gameplayFrames: Flow<GameplayFrame> = flow { }
            override suspend fun send(frame: ClientFrame) = Unit
        }

        override suspend fun leaveRoom(code: String): LeaveRoomOutcome {
            leftCodes += code
            return LeaveRoomOutcome.Success
        }

        override suspend fun joinRoom(code: String): JoinRoomOutcome {
            joinedCodes += code
            return join
        }

        override suspend fun createRoom(maxSeats: Int?, buyIn: Long?, open: Boolean): CreateRoomOutcome = error("unused")
        override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome = error("unused")
        override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome = error("unused")
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome = GetActiveRoomsOutcome.Success(emptyList())
        override fun observeActiveRooms(): Flow<List<Room>> = flow { }
    }

    private class AlwaysSignedInAuth : AuthRepository {
        private val authenticated: AuthState = AuthState.Authenticated(
            userId = "11111111-1111-1111-1111-111111111111",
            isAnonymous = true,
            email = null,
        )
        private val flow = MutableStateFlow<AuthState>(authenticated).asStateFlow()

        override suspend fun current(): AuthState = authenticated
        override fun observe(): Flow<AuthState> = flow
        override suspend fun retry(): AuthState = authenticated
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome = SignInOutcome.Success
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
            SignUpOutcome.VerificationRequired(email)
        override suspend fun refreshSession(): RefreshOutcome = RefreshOutcome.EmailConfirmed
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = ResendOutcome.Sent
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = SendResetOutcome.Sent
        override suspend fun signOut() = Unit
        override suspend fun deleteAccount(): DeleteAccountOutcome = DeleteAccountOutcome.Success
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome = LinkIdentityOutcome.Success
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
            LinkEmailIdentityOutcome.VerificationRequired(email)
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome = SignInOutcome.Success
    }
}

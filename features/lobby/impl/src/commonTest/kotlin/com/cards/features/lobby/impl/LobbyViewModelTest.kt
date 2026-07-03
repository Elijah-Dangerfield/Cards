package com.dangerfield.cards.features.lobby.impl

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
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
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GameplayFrame
import com.dangerfield.cards.libraries.rooms.RemoveBotOutcome
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [LobbyViewModel]'s state machine. Heavy use of in-memory fakes
 * for both repos so the assertions stay on the VM's branching, not the
 * underlying transport.
 *
 * What we pin:
 *  - codeInput is uppercased on every keystroke + canSubmitJoin gates
 *    on a sensible length.
 *  - Create → Success flips into the in-room state + starts observing
 *    the WS flow.
 *  - Create → Network error stays on the idle form + surfaces a
 *    friendly message.
 *  - Join → Full surfaces "room is full" without entering the in-room
 *    state.
 *  - Join with blank code surfaces an inline error without touching
 *    the repo.
 *  - Leave returns to Idle and cancels the WS subscription.
 *  - prefilledCode auto-triggers a join on init.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbyViewModelTest : CoroutineTest() {

    @Test
    fun codeInput_isUppercased() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(LobbyAction.CodeChanged("abc123"))
        vm.stateFlow.test {
            // The initial emission may be the default; advance to the
            // post-keystroke state.
            var last = awaitItem()
            while (last.codeInput != "ABC123") last = awaitItem()
            assertEquals("ABC123", last.codeInput)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun canSubmitJoin_isFalse_forShortCode_andTrue_for6Chars() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(LobbyAction.CodeChanged("AB"))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.codeInput != "AB") last = awaitItem()
            assertEquals(false, last.canSubmitJoin)
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.CodeChanged("ABC123"))
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.codeInput != "ABC123") last = awaitItem()
            assertEquals(true, last.canSubmitJoin)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun create_success_entersInRoomState_andSubscribesToFlow() = runUnitTest {
        val room = sampleRoom()
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            observe = { code -> flow { /* never emits — VM should still flip in-room from the seed */ } },
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            assertEquals(room, last.room)
            assertEquals(ConnectionStatus.Connected, last.connectionStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun create_forwardsHostEquippedFeltAndCardBack_asTableCosmetics() = runUnitTest {
        // SHOP-3: the host's equipped felt + card back ride along to createRoom so
        // the room snapshot carries them table-wide. A non-cosmetic equip (a tool)
        // is ignored — only the felt + card-back slots resolve.
        val rooms = FakeRoomRepository(createOutcome = CreateRoomOutcome.Success(sampleRoom()))
        val equipment = FakeEquipmentRepository(
            equipped = listOf("cardback_gold", "tool_win_odds", "felt_royal_red"),
        )
        val vm = buildVm(rooms = rooms, equipment = equipment)

        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("felt_royal_red", rooms.createdFeltProductId)
        assertEquals("cardback_gold", rooms.createdCardBackProductId)
    }

    @Test
    fun create_withNothingEquipped_forwardsNoTableCosmetics() = runUnitTest {
        val rooms = FakeRoomRepository(createOutcome = CreateRoomOutcome.Success(sampleRoom()))
        val vm = buildVm(rooms = rooms, equipment = FakeEquipmentRepository(equipped = emptyList()))

        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(null, rooms.createdFeltProductId)
        assertEquals(null, rooms.createdCardBackProductId)
    }

    @Test
    fun create_withPickedCosmetics_forwardsThePick_overEquipped() = runUnitTest {
        // SHOP-5: the create screen's explicit picker wins over the host's
        // equipped look — even though the host has felt/card back B equipped, the
        // picked A ids pin onto the room.
        val rooms = FakeRoomRepository(createOutcome = CreateRoomOutcome.Success(sampleRoom()))
        val equipment = FakeEquipmentRepository(equipped = listOf("cardback_gold", "felt_royal_red"))
        val vm = buildVm(
            rooms = rooms,
            equipment = equipment,
            pickedFeltProductId = "felt_midnight_blue",
            pickedCardBackProductId = "cardback_marble",
        )

        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("felt_midnight_blue", rooms.createdFeltProductId)
        assertEquals("cardback_marble", rooms.createdCardBackProductId)
    }

    @Test
    fun create_withOnlyFeltPicked_fallsBackToEquippedForCardBack() = runUnitTest {
        // A partial pick (only felt) still uses the equipped card back — each slot
        // resolves independently, picked-first then equipped-fallback.
        val rooms = FakeRoomRepository(createOutcome = CreateRoomOutcome.Success(sampleRoom()))
        val equipment = FakeEquipmentRepository(equipped = listOf("cardback_gold", "felt_royal_red"))
        val vm = buildVm(
            rooms = rooms,
            equipment = equipment,
            pickedFeltProductId = "felt_midnight_blue",
        )

        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("felt_midnight_blue", rooms.createdFeltProductId)
        assertEquals("cardback_gold", rooms.createdCardBackProductId)
    }

    @Test
    fun create_networkError_staysIdle_andSurfacesMessage() = runUnitTest {
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.NetworkError(RuntimeException("simulated network error")),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.CreateNetworkError, last.error)
            assertEquals(null, last.room)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun create_notSignedIn_readsAsSignInFirst() = runUnitTest {
        // NotSignedIn is honest by construction now: the repo only produces it
        // for a confirmed account problem (typed AuthUnready). Offline arrives
        // as NetworkError — no per-profile recoloring in the VM.
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.NotSignedIn(RuntimeException("auth")),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.CreateNotSignedIn, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun join_notSignedIn_readsAsSignInFirst() = runUnitTest {
        val rooms = FakeRoomRepository(
            joinOutcome = JoinRoomOutcome.NotSignedIn(RuntimeException("auth")),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CodeChanged("ABCDEF"))
        vm.takeAction(LobbyAction.SubmitJoin)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.JoinNotSignedIn, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun join_full_surfacesError_withoutEnteringInRoom() = runUnitTest {
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.Full)
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CodeChanged("ABCDEF"))
        vm.takeAction(LobbyAction.SubmitJoin)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.JoinRoomFull, last.error)
            assertEquals(null, last.room)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun join_blankCode_surfacesError_withoutTouchingRepo() = runUnitTest {
        val rooms = FakeRoomRepository()
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.SubmitJoin)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.JoinBlankCode, last.error)
            assertEquals(0, rooms.joinCalls, "blank code short-circuits before the network")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun join_notFound_surfacesError_withCode() = runUnitTest {
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.NotFound)
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CodeChanged("WXYZ12"))
        vm.takeAction(LobbyAction.SubmitJoin)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.JoinRoomNotFound(code = "WXYZ12"), last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun createRoom_invalidMaxSeats_carriesServerMessage() = runUnitTest {
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.InvalidMaxSeats("maxSeats must be 2..9"),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            val err = assertIs<LobbyError.CreateInvalidMaxSeats>(last.error)
            assertEquals("maxSeats must be 2..9", err.message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun startGame_outsideRoom_noOps() = runUnitTest {
        // StartGame requires canStart (host + ≥2 members + live handle).
        // From the idle screen none of that holds, so the action should
        // silently no-op — no error, no event.
        val vm = buildVm()
        vm.takeAction(LobbyAction.StartGame)

        assertEquals(null, vm.state.error)
        // The handler returns early so no NavigateToMultiplayer event
        // is emitted either.
    }

    @Test
    fun dismissError_clearsTheState() = runUnitTest {
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.Full)
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CodeChanged("ABCDEF"))
        vm.takeAction(LobbyAction.SubmitJoin)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.DismissError)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error != null) last = awaitItem()
            assertEquals(null, last.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun prefilledCode_autoTriggersJoin() = runUnitTest {
        val room = sampleRoom(code = "PREFIL")
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.Success(room, false))
        val vm = buildVm(rooms = rooms, prefilledCode = "prefil")

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            assertEquals("PREFIL", last.room?.code)
            assertEquals(1, rooms.joinCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun prefilledJoin_notFound_emitsJoinCodeRejected_notInlineError() = runUnitTest {
        // The PrivateJoin → Lobby funnel: a bad prefilled code must bounce back
        // to the input screen (event) rather than strand the user on a dead
        // lobby spinner with an inline error (CARDS-28).
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.NotFound)
        val vm = buildVm(rooms = rooms, prefilledCode = "WXYZ12")

        vm.eventFlow.test {
            val event = awaitItem()
            val rejected = assertIs<LobbyEvent.JoinCodeRejected>(event)
            assertEquals("WXYZ12", rejected.code)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(null, vm.state.error)
    }

    @Test
    fun leave_serverCallSurvivesViewModelTeardown() = runUnitTest {
        // Fire-and-forget contract: the server-side `leaveRoom` POST must
        // complete even if the user pops the lobby screen mid-call.
        // Without launching into AppCoroutineScope, viewModelScope's
        // cancellation would tear down the in-flight HTTP call.
        val room = sampleRoom()
        val gate = CompletableDeferred<LeaveRoomOutcome>()
        val rooms = ControllableRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            leaveGate = gate,
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.Leave)
        runCurrent()
        assertEquals(1, rooms.leaveStarted, "leaveRoom should be in-flight after Leave action")

        vm.viewModelScope.coroutineContext.job.cancel()
        runCurrent()

        gate.complete(LeaveRoomOutcome.Success())
        runCurrent()
        assertEquals(1, rooms.leaveFinished, "leaveRoom must complete despite VM teardown")
    }

    @Test
    fun leave_returnsToIdle_andCancelsConnection() = runUnitTest {
        val room = sampleRoom()
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            leaveOutcome = LeaveRoomOutcome.Success(),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        // Wait until we're in the room.
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.Leave)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room != null) last = awaitItem()
            assertEquals(null, last.room)
            assertEquals(ConnectionStatus.Disconnected, last.connectionStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun leave_realChipRoom_reSyncsTheWallet() = runUnitTest {
        // MP-27: after an opponent-left kick collapses the play screen back to
        // the lobby, leaving the lobby must re-pull the authoritative balance —
        // otherwise the buy-in shows as still escrowed until the next foreground
        // forces a sync (CARDS-5Q).
        val room = roomWithStakes(
            buyIn = 5000,
            smallBlind = 25,
            bigBlind = 50,
            members = listOf(member(LOCAL_USER, "You", isConnected = true)),
        )
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            leaveOutcome = LeaveRoomOutcome.Success(),
        )
        val chips = FakeChipsRepository()
        val vm = buildVm(rooms = rooms, chips = chips)
        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.Leave)
        runCurrent()

        assertEquals(1, chips.syncCalls, "leaving a real-chip room re-syncs the wallet")
    }

    @Test
    fun leave_realChipRoom_settledBalance_appliesDirectly_noSync() = runUnitTest {
        // MP-29: when the leave returns the server's post-cash-out balance, the
        // lobby applies it directly instead of a speculative sync racing the
        // settlement commit (CARDS-5Q).
        val room = roomWithStakes(
            buyIn = 5000,
            smallBlind = 25,
            bigBlind = 50,
            members = listOf(member(LOCAL_USER, "You", isConnected = true)),
        )
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            leaveOutcome = LeaveRoomOutcome.Success(settledBalance = 8_800L),
        )
        val chips = FakeChipsRepository()
        val vm = buildVm(rooms = rooms, chips = chips)
        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.Leave)
        runCurrent()

        assertEquals(8_800L, chips.lastSetBalance, "applies the server's settled balance")
        assertEquals(0, chips.syncCalls, "a settled leave must not also fire a sync")
    }

    @Test
    fun leave_freeTable_doesNotSyncTheWallet() = runUnitTest {
        // A free table (buyIn == 0) never escrowed chips, so there's nothing to
        // reconcile — leaving must not fire a needless wallet sync.
        val room = sampleRoom()
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            leaveOutcome = LeaveRoomOutcome.Success(),
        )
        val chips = FakeChipsRepository()
        val vm = buildVm(rooms = rooms, chips = chips)
        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.Leave)
        runCurrent()

        assertEquals(0, chips.syncCalls, "a free table has no escrow to reconcile")
    }

    @Test
    fun createError_isExposed_whenACreateCallFailsOutsideARoom() = runUnitTest {
        // CARDS-2E: a failed create call should drive the full-screen retry
        // state, not strand the user on the "Setting up…" spinner.
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.NetworkError(RuntimeException("boom")),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.CreateNetworkError, last.createError)
            assertFalse(last.creating, "the spinner is gone once the create error lands")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun createError_isNull_forJoinErrors() = runUnitTest {
        // A join failure keeps the inline-error treatment, not the full-screen one.
        val state = LobbyState(error = LobbyError.JoinRoomFull)
        assertNull(state.createError)
    }

    @Test
    fun createError_isNull_whileCreating() = runUnitTest {
        // Mid-retry the spinner shows again, not the error screen.
        val state = LobbyState(creating = true, error = LobbyError.CreateNetworkError)
        assertNull(state.createError)
    }

    // ---------- new MP paths (B6 Round 1) ----------

    @Test
    fun startGame_hostInRoomWith2Members_sendsStartHandFrame_andEmitsNavigateEvent() = runUnitTest {
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(
                    members = listOf(
                        member(LOCAL_USER, "You", isConnected = true),
                        member("peer", "Peer", isConnected = true, seatIndex = 1),
                    ),
                ),
            ),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()
        assertTrue(vm.state.canStart, "host with 2 connected members should be able to start")

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.StartGame)
            val event = assertIs<LobbyEvent.NavigateToMultiplayer>(awaitItem())
            assertEquals("ABC123", event.roomCode)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, rooms.handle.sent.count { it is ClientFrame.StartHand })
    }

    @Test
    fun addBot_host_callsRepositoryWithSeatIndex() = runUnitTest {
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true))),
            ),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()
        assertTrue(vm.state.isHost)

        vm.takeAction(LobbyAction.AddBot(seatIndex = 1))
        runCurrent()

        assertEquals(listOf<Int?>(1), rooms.addBotSeatIndexes)
    }

    @Test
    fun addBot_nonHost_isNoOp() = runUnitTest {
        // Effective host is "peer"; the local user isn't host, so AddBot must
        // not reach the repository.
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(
                    members = listOf(
                        member("peer", "Peer", isConnected = true),
                        member(LOCAL_USER, "You", isConnected = true, seatIndex = 1),
                    ),
                ),
            ),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()
        assertFalse(vm.state.isHost)

        vm.takeAction(LobbyAction.AddBot(seatIndex = 2))
        runCurrent()

        assertTrue(rooms.addBotSeatIndexes.isEmpty())
    }

    @Test
    fun startGame_nonHost_isNoOp() = runUnitTest {
        // Effective host is the first connected member ("peer"), not the
        // local user, so canStart is false and Start must do nothing.
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(
                    members = listOf(
                        member("peer", "Peer", isConnected = true),
                        member(LOCAL_USER, "You", isConnected = true, seatIndex = 1),
                    ),
                ),
            ),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()
        assertFalse(vm.state.canStart)

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.StartGame)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(rooms.handle.sent.none { it is ClientFrame.StartHand })
    }

    @Test
    fun startGame_hostAlone_isNoOp() = runUnitTest {
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true))),
            ),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()
        assertFalse(vm.state.canStart, "a lone host (members < 2) cannot start")

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.StartGame)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(rooms.handle.sent.none { it is ClientFrame.StartHand })
    }

    @Test
    fun gameplaySnapshotReceived_nonHost_emitsNavigateEvent() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(
            LobbyAction.ConnectionUpdated(
                RoomConnection.Connected(
                    roomOf(
                        members = listOf(
                            member("peer", "Peer", isConnected = true),
                            member(LOCAL_USER, "You", isConnected = true, seatIndex = 1),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.GameplaySnapshotReceived)
            val event = assertIs<LobbyEvent.NavigateToMultiplayer>(awaitItem())
            assertEquals("ABC123", event.roomCode)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.state.hasReceivedGameplaySnapshot)
    }

    @Test
    fun gameplaySnapshotReceived_host_doesNotEmitNavigateAgain() = runUnitTest {
        // The host already navigated when they tapped Start; the first
        // snapshot must not push them onto the play screen a second time.
        val vm = buildVm()
        vm.takeAction(
            LobbyAction.ConnectionUpdated(
                RoomConnection.Connected(
                    roomOf(
                        members = listOf(
                            member(LOCAL_USER, "You", isConnected = true),
                            member("peer", "Peer", isConnected = true, seatIndex = 1),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        assertTrue(vm.state.isHost)

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.GameplaySnapshotReceived)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.state.hasReceivedGameplaySnapshot)
    }

    @Test
    fun gameplaySnapshotReceived_secondCall_doesNotReEmit() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(
            LobbyAction.ConnectionUpdated(
                RoomConnection.Connected(
                    roomOf(
                        members = listOf(
                            member("peer", "Peer", isConnected = true),
                            member(LOCAL_USER, "You", isConnected = true, seatIndex = 1),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.GameplaySnapshotReceived)
            assertIs<LobbyEvent.NavigateToMultiplayer>(awaitItem())
            vm.takeAction(LobbyAction.GameplaySnapshotReceived)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun effectiveHostUserId_allConnected_isFirstMember() = runUnitTest {
        val state = LobbyState(
            room = roomOf(
                members = listOf(
                    member("a", "A", isConnected = true),
                    member("b", "B", isConnected = true, seatIndex = 1),
                ),
            ),
        )
        assertEquals("a", state.effectiveHostUserId)
    }

    @Test
    fun effectiveHostUserId_firstMemberDisconnected_promotesNextConnected() = runUnitTest {
        val state = LobbyState(
            room = roomOf(
                members = listOf(
                    member("a", "A", isConnected = false),
                    member("b", "B", isConnected = true, seatIndex = 1),
                ),
            ),
        )
        assertEquals("b", state.effectiveHostUserId)
    }

    @Test
    fun effectiveHostUserId_noConnectedMembers_isNull() = runUnitTest {
        val state = LobbyState(
            room = roomOf(
                members = listOf(
                    member("a", "A", isConnected = false),
                    member("b", "B", isConnected = false, seatIndex = 1),
                ),
            ),
        )
        assertNull(state.effectiveHostUserId)
    }

    @Test
    fun effectiveHostUserId_originalHostReconnects_returnsToOriginal() = runUnitTest {
        // While "a" was down, "b" was effective host; once "a" reconnects
        // it reclaims host because it sits first in the member list.
        val promoted = LobbyState(
            room = roomOf(
                members = listOf(
                    member("a", "A", isConnected = false),
                    member("b", "B", isConnected = true, seatIndex = 1),
                ),
            ),
        )
        assertEquals("b", promoted.effectiveHostUserId)

        val reconnected = promoted.copy(
            room = roomOf(
                members = listOf(
                    member("a", "A", isConnected = true),
                    member("b", "B", isConnected = true, seatIndex = 1),
                ),
            ),
        )
        assertEquals("a", reconnected.effectiveHostUserId)
    }

    @Test
    fun connectionUpdated_hostChanges_emitsHostPromotedEvent() = runUnitTest {
        val vm = buildVm()
        // First snapshot seeds the room with the local user as host —
        // no promotion event (previous host was null).
        vm.takeAction(
            LobbyAction.ConnectionUpdated(
                RoomConnection.Connected(
                    roomOf(
                        members = listOf(
                            member(LOCAL_USER, "You", isConnected = true),
                            member("peer", "Peer", isConnected = true, seatIndex = 1),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()

        vm.eventFlow.test {
            // Local user drops; "Peer" becomes effective host.
            vm.takeAction(
                LobbyAction.ConnectionUpdated(
                    RoomConnection.Connected(
                        roomOf(
                            members = listOf(
                                member(LOCAL_USER, "You", isConnected = false),
                                member("peer", "Peer", isConnected = true, seatIndex = 1),
                            ),
                        ),
                    ),
                ),
            )
            val event = assertIs<LobbyEvent.HostPromoted>(awaitItem())
            assertEquals("Peer", event.newHostDisplayName)
            assertFalse(event.isLocalUser)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun connectionUpdated_hostUnchanged_doesNotEmitPromotion() = runUnitTest {
        val room = roomOf(
            members = listOf(
                member(LOCAL_USER, "You", isConnected = true),
                member("peer", "Peer", isConnected = true, seatIndex = 1),
            ),
        )
        val vm = buildVm()
        vm.takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Connected(room)))
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Connected(room)))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun connectionUpdated_initialSetup_doesNotEmitPromotion() = runUnitTest {
        val vm = buildVm()
        vm.eventFlow.test {
            vm.takeAction(
                LobbyAction.ConnectionUpdated(
                    RoomConnection.Connected(
                        roomOf(
                            members = listOf(
                                member(LOCAL_USER, "You", isConnected = true),
                                member("peer", "Peer", isConnected = true, seatIndex = 1),
                            ),
                        ),
                    ),
                ),
            )
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- MP-24: joiner buy-in never regresses to $0 ----------

    @Test
    fun join_realBuyIn_thenPlaceholderPresenceSnapshot_keepsRealBuyIn() = runUnitTest {
        // The joiner's HTTP join response carries the real stakes (server
        // debited the buy-in). The first socket frame is a lobby presence
        // snapshot with the converged member list but buyIn = 0 (the server's
        // presence snapshots don't carry stakes). The lobby must keep the real
        // buy-in while still adopting the live member list (MP-24).
        val joined = roomWithStakes(
            buyIn = 5000,
            smallBlind = 25,
            bigBlind = 50,
            members = listOf(member(LOCAL_USER, "You", isConnected = true)),
        )
        val presenceSnapshot = roomWithStakes(
            buyIn = 0,
            smallBlind = 0,
            bigBlind = 0,
            members = listOf(
                member(LOCAL_USER, "You", isConnected = true),
                member("peer", "Peer", isConnected = true, seatIndex = 1),
            ),
        )
        val socketFrames = Channel<RoomConnection>(Channel.UNLIMITED)
        val rooms = FakeRoomRepository(
            joinOutcome = JoinRoomOutcome.Success(joined, alreadyJoined = false),
            observe = { socketFrames.receiveAsFlow() },
        )
        val vm = buildVm(rooms = rooms, prefilledCode = "AS4UPA")

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room?.buyIn != 5000L) last = awaitItem()
            assertEquals(1, last.room.members.size, "join response seeds one seat")

            socketFrames.send(RoomConnection.Connected(presenceSnapshot))
            var afterSnapshot = awaitItem()
            while (afterSnapshot.room?.members?.size != 2) afterSnapshot = awaitItem()
            val converged = afterSnapshot.room

            assertEquals(2, converged.members.size, "the live member list converged")
            assertEquals(5000L, converged.buyIn, "buy-in must not regress to 0")
            assertEquals(25L, converged.smallBlind)
            assertEquals(50L, converged.bigBlind)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- "open to anyone" (server-dealt) ----------

    @Test
    fun canStart_isFalse_forAServerDealtOpenTable_evenAsHostWithTwoPlayers() = runUnitTest {
        val state = LobbyState(
            currentUserId = "a",
            room = roomOf(
                visibility = com.dangerfield.cards.libraries.rooms.RoomVisibility.Open,
                members = listOf(
                    member("a", "A", isConnected = true),
                    member("b", "B", isConnected = true, seatIndex = 1),
                ),
            ),
        )
        assertTrue(state.isHost)
        assertTrue(state.isServerDealtTable)
        assertFalse(state.canStart, "an Open table deals itself — no host Start")
    }

    @Test
    fun gameplaySnapshot_onAServerDealtTable_navigatesEvenTheHost() = runUnitTest {
        // On a Private table the host navigates only when they tap Start; on an
        // Open (server-dealt) table the host follows the first deal in like anyone.
        val vm = buildVm()
        vm.takeAction(
            LobbyAction.ConnectionUpdated(
                RoomConnection.Connected(
                    roomOf(
                        visibility = com.dangerfield.cards.libraries.rooms.RoomVisibility.Open,
                        members = listOf(
                            member(LOCAL_USER, "You", isConnected = true),
                            member("peer", "Peer", isConnected = true, seatIndex = 1),
                        ),
                    ),
                ),
            ),
        )
        runCurrent()
        assertTrue(vm.state.isHost, "local user is the host of the Open table")

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.GameplaySnapshotReceived)
            val event = assertIs<LobbyEvent.NavigateToMultiplayer>(awaitItem())
            assertEquals("ABC123", event.roomCode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- ConnectionUpdated: socket-close reasons map to lobby errors ----------

    @Test
    fun connectionUpdated_closedRoomDeleted_clearsRoom_andSurfacesRoomWasClosed() = runUnitTest {
        val vm = seededInRoomVm()

        vm.takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Closed(ClosedReason.RoomDeleted)))
        runCurrent()

        assertNull(vm.state.room, "a deleted room clears the lobby")
        assertEquals(LobbyError.RoomWasClosed, vm.state.error)
        assertEquals(ConnectionStatus.Disconnected, vm.state.connectionStatus)
    }

    @Test
    fun connectionUpdated_closedRejected_clearsRoom_andSurfacesConnectRejected() = runUnitTest {
        val vm = seededInRoomVm()

        vm.takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Closed(ClosedReason.Rejected)))
        runCurrent()

        assertNull(vm.state.room)
        assertEquals(LobbyError.ConnectRejected, vm.state.error)
        assertEquals(ConnectionStatus.Disconnected, vm.state.connectionStatus)
    }

    @Test
    fun connectionUpdated_closedIncompatibleVersion_clearsRoom_asIfGone() = runUnitTest {
        // ENG-7: a frame this build can't parse makes the room unusable, so it's
        // closed out like the room being gone rather than left spinning.
        val vm = seededInRoomVm()

        vm.takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Closed(ClosedReason.IncompatibleVersion)))
        runCurrent()

        assertNull(vm.state.room)
        assertEquals(LobbyError.RoomWasClosed, vm.state.error)
    }

    @Test
    fun connectionUpdated_closedReconnectFailed_keepsRoom_butSurfacesConnectionLost() = runUnitTest {
        // Reconnect exhaustion is recoverable-by-rejoin: keep the room on screen
        // (so the user can retry the leave/rejoin flow) but flag the lost link.
        val vm = seededInRoomVm()

        vm.takeAction(LobbyAction.ConnectionUpdated(RoomConnection.Closed(ClosedReason.ReconnectFailed)))
        runCurrent()

        assertEquals(LobbyError.ConnectionLost, vm.state.error)
        assertEquals(ConnectionStatus.Disconnected, vm.state.connectionStatus)
        assertTrue(vm.state.isInRoom, "a reconnect failure keeps the room so the user can rejoin")
    }

    @Test
    fun leave_networkError_returnsToIdle_butFlagsServerNotNotified() = runUnitTest {
        // The seat-drop POST never reached the server. We still tear the lobby
        // down (the user asked to leave), but surface that the server may not
        // know, so a lingering seat is explainable rather than silent.
        val room = sampleRoom()
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            leaveOutcome = LeaveRoomOutcome.NetworkError(RuntimeException("offline")),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room == null) last = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        vm.takeAction(LobbyAction.Leave)
        vm.stateFlow.test {
            var last = awaitItem()
            while (last.room != null) last = awaitItem()
            assertEquals(LobbyError.LeaveServerNotNotified, last.error)
            assertEquals(ConnectionStatus.Disconnected, last.connectionStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------- scaffolding ----------

    /** A VM already in a room via a Connected snapshot, ready to drive a socket close against. */
    private fun TestScope.seededInRoomVm(): LobbyViewModel {
        val vm = buildVm()
        vm.takeAction(
            LobbyAction.ConnectionUpdated(
                RoomConnection.Connected(
                    roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true))),
                ),
            ),
        )
        runCurrent()
        assertTrue(vm.state.isInRoom, "precondition: seeded into a room")
        return vm
    }

    private fun buildVm(
        rooms: RoomRepository = FakeRoomRepository(),
        identity: AuthRepository = AlwaysSignedInAuth(),
        profile: com.dangerfield.cards.libraries.identity.profile.ProfileRepository = NoProfileRepository,
        prefilledCode: String? = null,
        autoCreate: Boolean = false,
        maxSeats: Int? = null,
        buyIn: Long? = null,
        open: Boolean = false,
        pickedFeltProductId: String? = null,
        pickedCardBackProductId: String? = null,
        equipment: EquipmentRepository = FakeEquipmentRepository(),
        chips: ChipsRepository = FakeChipsRepository(),
    ): LobbyViewModel = LobbyViewModel(
        prefilledCode = prefilledCode,
        autoCreate = autoCreate,
        maxSeats = maxSeats,
        buyIn = buyIn,
        open = open,
        pickedFeltProductId = pickedFeltProductId,
        pickedCardBackProductId = pickedCardBackProductId,
        rooms = rooms,
        auth = identity,
        profile = profile,
        equipment = equipment,
        chips = chips,
        appScope = AppCoroutineScope(dispatchers),
    )

    /** Lobby tests don't exercise the avatar; never emits a profile. */
    private object NoProfileRepository : com.dangerfield.cards.libraries.identity.profile.ProfileRepository {
        override suspend fun current() = error("not used")
        override fun observe() = kotlinx.coroutines.flow.emptyFlow<com.dangerfield.cards.libraries.identity.profile.Profile>()
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ) = error("not used")
        override suspend fun fetchAvatarPack() = error("not used")
    }

    private val LOCAL_USER = "11111111-1111-1111-1111-111111111111"

    private fun member(
        userId: String,
        displayName: String,
        isConnected: Boolean,
        seatIndex: Int = 0,
    ) = RoomMember(
        userId = userId,
        displayName = displayName,
        seatIndex = seatIndex,
        joinedAtEpochMs = 1_700_000_000_000,
        isConnected = isConnected,
    )

    private fun roomOf(
        code: String = "ABC123",
        members: List<RoomMember>,
        visibility: com.dangerfield.cards.libraries.rooms.RoomVisibility =
            com.dangerfield.cards.libraries.rooms.RoomVisibility.Private,
    ) = Room(
        code = code,
        hostUserId = members.first().userId,
        createdAtEpochMs = 1_700_000_000_000,
        maxSeats = 4,
        status = RoomStatus.Lobby,
        members = members,
        visibility = visibility,
    )

    private fun roomWithStakes(
        buyIn: Long,
        smallBlind: Long,
        bigBlind: Long,
        members: List<RoomMember>,
        code: String = "AS4UPA",
    ) = Room(
        code = code,
        hostUserId = members.first().userId,
        createdAtEpochMs = 1_700_000_000_000,
        maxSeats = 4,
        status = RoomStatus.Lobby,
        members = members,
        buyIn = buyIn,
        smallBlind = smallBlind,
        bigBlind = bigBlind,
    )

    /**
     * [RoomRepository] whose [connect] hands back a [RecordingHandle] so
     * the StartGame tests can assert the outbound [ClientFrame.StartHand]
     * actually reached the socket.
     */
    private class RecordingRoomRepository(
        private val createOutcome: CreateRoomOutcome,
        val handle: RecordingHandle = RecordingHandle(),
    ) : RoomRepository {
        val addBotSeatIndexes: MutableList<Int?> = mutableListOf()
        override suspend fun createRoom(
            maxSeats: Int?,
            buyIn: Long?,
            open: Boolean,
            feltProductId: String?,
            cardBackProductId: String?,
        ): CreateRoomOutcome = createOutcome
        override suspend fun joinRoom(code: String): JoinRoomOutcome =
            JoinRoomOutcome.NetworkError(RuntimeException("not used"))
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = LeaveRoomOutcome.Success()
        override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome {
            addBotSeatIndexes += seatIndex
            return AddBotOutcome.Success(
                Room(
                    code = code,
                    hostUserId = "host",
                    createdAtEpochMs = 0,
                    maxSeats = 4,
                    status = RoomStatus.Lobby,
                    members = emptyList(),
                ),
            )
        }
        override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome =
            RemoveBotOutcome.Success
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome =
            GetActiveRoomsOutcome.Success(emptyList())
        override fun observeActiveRooms(): Flow<List<Room>> = flow { }
        override fun connect(code: String): RoomConnectionHandle = handle
    }

    private class RecordingHandle : RoomConnectionHandle {
        val sent: MutableList<ClientFrame> = mutableListOf()
        override val connection: Flow<RoomConnection> = flow { }
        override val gameplayFrames: Flow<GameplayFrame> = flow { }
        override suspend fun send(frame: ClientFrame) { sent += frame }
    }

    private fun sampleRoom(code: String = "ABC123") = Room(
        code = code,
        hostUserId = "11111111-1111-1111-1111-111111111111",
        createdAtEpochMs = 1_700_000_000_000,
        maxSeats = 4,
        status = RoomStatus.Lobby,
        members = listOf(
            RoomMember(
                userId = "11111111-1111-1111-1111-111111111111",
                displayName = "Host",
                seatIndex = 0,
                joinedAtEpochMs = 1_700_000_000_000,
                isConnected = false,
            ),
        ),
    )

    /**
     * Variant of [FakeRoomRepository] that lets a test gate `leaveRoom`
     * on an external [CompletableDeferred] and observe whether the call
     * actually completed (vs. being cancelled mid-flight).
     */
    private class ControllableRoomRepository(
        private val createOutcome: CreateRoomOutcome,
        private val leaveGate: CompletableDeferred<LeaveRoomOutcome>,
    ) : RoomRepository {
        var leaveStarted: Int = 0
            private set
        var leaveFinished: Int = 0
            private set

        override suspend fun createRoom(
            maxSeats: Int?,
            buyIn: Long?,
            open: Boolean,
            feltProductId: String?,
            cardBackProductId: String?,
        ): CreateRoomOutcome = createOutcome
        override suspend fun joinRoom(code: String): JoinRoomOutcome =
            JoinRoomOutcome.NetworkError(RuntimeException("not used"))
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome {
            leaveStarted += 1
            val outcome = leaveGate.await()
            leaveFinished += 1
            return outcome
        }
        override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome = error("unused")
        override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome = error("unused")
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome =
            GetActiveRoomsOutcome.Success(emptyList())
        override fun observeActiveRooms(): Flow<List<Room>> = flow { }
        override fun connect(code: String): RoomConnectionHandle = EmptyHandle
    }

    private class FakeRoomRepository(
        private val createOutcome: CreateRoomOutcome = CreateRoomOutcome.NetworkError(RuntimeException("simulated network error")),
        private val joinOutcome: JoinRoomOutcome = JoinRoomOutcome.NetworkError(RuntimeException("simulated network error")),
        private val leaveOutcome: LeaveRoomOutcome = LeaveRoomOutcome.Success(),
        private val activeRoomsOutcome: GetActiveRoomsOutcome = GetActiveRoomsOutcome.Success(emptyList()),
        private val observe: (String) -> Flow<RoomConnection> = { flow {} },
    ) : RoomRepository {
        var joinCalls: Int = 0
            private set
        val addBotSeatIndexes: MutableList<Int?> = mutableListOf()
        var createdFeltProductId: String? = null
            private set
        var createdCardBackProductId: String? = null
            private set
        override suspend fun createRoom(
            maxSeats: Int?,
            buyIn: Long?,
            open: Boolean,
            feltProductId: String?,
            cardBackProductId: String?,
        ): CreateRoomOutcome {
            createdFeltProductId = feltProductId
            createdCardBackProductId = cardBackProductId
            return createOutcome
        }
        override suspend fun joinRoom(code: String): JoinRoomOutcome {
            joinCalls += 1
            return joinOutcome
        }
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = leaveOutcome
        override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome {
            addBotSeatIndexes += seatIndex
            return AddBotOutcome.NetworkError(RuntimeException("not used"))
        }
        override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome =
            RemoveBotOutcome.Success
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome = activeRoomsOutcome
        override fun observeActiveRooms(): Flow<List<Room>> = flow { }
        override fun connect(code: String): RoomConnectionHandle = object : RoomConnectionHandle {
            override val connection: Flow<RoomConnection> = observe(code)
            override val gameplayFrames: Flow<GameplayFrame> = flow { }
            override suspend fun send(frame: ClientFrame) = Unit
        }
    }

    private object EmptyHandle : RoomConnectionHandle {
        override val connection: Flow<RoomConnection> = flow { }
        override val gameplayFrames: Flow<GameplayFrame> = flow { }
        override suspend fun send(frame: ClientFrame) = Unit
    }

    /**
     * Equipment fake seeded with whatever the host has "equipped" — newest-first,
     * mirroring the real DAO order. Only [observeEquipped] is exercised by the
     * create path; the rest are no-ops.
     */
    private class FakeEquipmentRepository(
        equipped: List<String> = emptyList(),
    ) : EquipmentRepository {
        private val state = MutableStateFlow(
            equipped.mapIndexed { index, productId ->
                EquipmentEntry(
                    productId = productId,
                    isEquipped = true,
                    syncState = EquipmentSyncState.Synced,
                    updatedAtEpochMs = (equipped.size - index).toLong(),
                )
            },
        )
        override fun observeEquipped(): Flow<List<EquipmentEntry>> = state.asStateFlow()
        override suspend fun getAll(): List<EquipmentEntry> = state.value
        override suspend fun equip(productId: String): EquipmentToggleResult = EquipmentToggleResult.NoChange
        override suspend fun unequip(productId: String): EquipmentToggleResult = EquipmentToggleResult.NoChange
        override suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>) = Unit
        override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> = emptyList()
        override suspend fun deleteAll() = Unit
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    /**
     * Records [sync] calls so MP-27 can assert the lobby Leave re-pulls the
     * wallet for a real-chip room. Balance reads are inert — the lobby fix
     * only needs the sync to fire; the celebratory credit confirmation lives
     * on the play screen.
     */
    private class FakeChipsRepository : ChipsRepository {
        var syncCalls: Int = 0
            private set
        var lastSetBalance: Long? = null
            private set
        private val balance = MutableStateFlow<Long?>(1000L)
        override fun observeBalance(): Flow<Long?> = balance.asStateFlow()
        override suspend fun getBalance(): Long? = balance.value
        override val walletJustCreated: kotlinx.coroutines.flow.StateFlow<Boolean> =
            MutableStateFlow(false).asStateFlow()
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) = Unit
        override suspend fun setBalance(authoritativeBalance: Long) {
            lastSetBalance = authoritativeBalance
            balance.value = authoritativeBalance
        }
        override suspend fun deleteAll() = Unit
        override suspend fun sync(): Result<Unit> {
            syncCalls += 1
            return Result.success(Unit)
        }
    }

    private class AlwaysSignedInAuth : AuthRepository {
        private val authenticated: AuthState = AuthState.Authenticated(
            userId = "11111111-1111-1111-1111-111111111111",
            isAnonymous = true,
            email = null,
        )
        private val flow = MutableStateFlow<AuthState>(authenticated).asStateFlow()

        override suspend fun current(): AuthState = authenticated
        override fun observe(): kotlinx.coroutines.flow.Flow<AuthState> = flow
        override suspend fun retry(): AuthState = authenticated
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
            SignInOutcome.Success
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
            SignUpOutcome.VerificationRequired(email)
        override suspend fun refreshSession(): RefreshOutcome = RefreshOutcome.EmailConfirmed
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = ResendOutcome.Sent
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome = SendResetOutcome.Sent
        override suspend fun signOut() { /* no-op */ }
        override suspend fun deleteAccount(): DeleteAccountOutcome = DeleteAccountOutcome.Success
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
            LinkIdentityOutcome.Success
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
            LinkEmailIdentityOutcome.VerificationRequired(email)
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
            SignInOutcome.Success
    }
}

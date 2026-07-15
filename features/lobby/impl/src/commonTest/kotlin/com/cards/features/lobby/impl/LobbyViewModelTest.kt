package com.dangerfield.cards.features.lobby.impl

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.ClientFrame
import com.dangerfield.cards.libraries.rooms.ClosedReason
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.RoomVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
 * (see LobbyFakes.kt) for both repos so the assertions stay on the VM's
 * branching, not the underlying transport.
 *
 * What we pin:
 *  - Create → Success flips into the in-room state + starts observing
 *    the WS flow, forwarding the picked/equipped table cosmetics.
 *  - Create → Network error drives the full-screen retry state.
 *  - prefilledCode auto-triggers a join on init; failures surface inline
 *    (or bounce back to the code-entry screen for an unknown code).
 *  - Leave returns to Idle, cancels the WS subscription, and reconciles
 *    the wallet for a real-chip room.
 *  - Host-only actions (StartGame, Add/RemoveBot) no-op for non-hosts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LobbyViewModelTest : CoroutineTest() {

    @Test
    fun create_success_entersInRoomState_andSubscribesToFlow() = runUnitTest {
        val room = sampleRoom()
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            observe = { flow { /* never emits — VM should still flip in-room from the seed */ } },
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
        runCurrent()

        assertTrue(vm.state.isInRoom)
        assertEquals("felt_royal_red", rooms.createdFeltProductId)
        assertEquals("cardback_gold", rooms.createdCardBackProductId)
    }

    @Test
    fun create_withNothingEquipped_forwardsNoTableCosmetics() = runUnitTest {
        val rooms = FakeRoomRepository(createOutcome = CreateRoomOutcome.Success(sampleRoom()))
        val vm = buildVm(rooms = rooms, equipment = FakeEquipmentRepository(equipped = emptyList()))

        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()

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
        runCurrent()

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
        runCurrent()

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
        runCurrent()

        assertEquals(LobbyError.CreateNetworkError, vm.state.error)
        assertEquals(null, vm.state.room)
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
        runCurrent()

        assertEquals(LobbyError.CreateNotSignedIn, vm.state.error)
    }

    @Test
    fun join_notSignedIn_readsAsSignInFirst() = runUnitTest {
        val rooms = FakeRoomRepository(
            joinOutcome = JoinRoomOutcome.NotSignedIn(RuntimeException("auth")),
        )
        val vm = buildVm(rooms = rooms, prefilledCode = "ABCDEF")
        runCurrent()

        assertEquals(LobbyError.JoinNotSignedIn, vm.state.error)
    }

    @Test
    fun join_full_surfacesError_withoutEnteringInRoom() = runUnitTest {
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.Full)
        val vm = buildVm(rooms = rooms, prefilledCode = "ABCDEF")
        runCurrent()

        assertEquals(LobbyError.JoinRoomFull, vm.state.error)
        assertEquals(null, vm.state.room)
    }

    @Test
    fun createRoom_invalidMaxSeats_carriesServerMessage() = runUnitTest {
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.InvalidMaxSeats("maxSeats must be 2..9"),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()

        val err = assertIs<LobbyError.CreateInvalidMaxSeats>(vm.state.error)
        assertEquals("maxSeats must be 2..9", err.message)
    }

    @Test
    fun startGame_outsideRoom_noOps() = runUnitTest {
        // StartGame requires canStart (host + ≥2 members + live handle).
        // From the setting-up screen none of that holds, so the action should
        // silently no-op — no error, no event.
        val vm = buildVm()
        vm.takeAction(LobbyAction.StartGame)
        runCurrent()

        assertEquals(null, vm.state.error)
        // The handler returns early so no NavigateToMultiplayer event
        // is emitted either.
    }

    @Test
    fun dismissError_clearsTheState() = runUnitTest {
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.Full)
        val vm = buildVm(rooms = rooms, prefilledCode = "ABCDEF")
        runCurrent()
        assertEquals(LobbyError.JoinRoomFull, vm.state.error)

        vm.takeAction(LobbyAction.DismissError)
        runCurrent()

        assertNull(vm.state.error)
    }

    @Test
    fun prefilledCode_autoTriggersJoin() = runUnitTest {
        val room = sampleRoom(code = "PREFIL")
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.Success(room, false))
        val vm = buildVm(rooms = rooms, prefilledCode = "prefil")
        runCurrent()

        assertEquals("PREFIL", vm.state.room?.code)
        assertEquals(1, rooms.joinCalls)
    }

    @Test
    fun prefilledJoin_notFound_emitsJoinCodeRejected_notInlineError() = runUnitTest {
        // The PrivateJoin → Lobby funnel: a bad prefilled code must bounce back
        // to the input screen (event) rather than strand the user on a dead
        // lobby spinner with an inline error (CARDS-28).
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.NotFound)
        val vm = buildVm(rooms = rooms, prefilledCode = "wxyz12")

        vm.eventFlow.test {
            val rejected = assertIs<LobbyEvent.JoinCodeRejected>(awaitItem())
            assertEquals("WXYZ12", rejected.code, "the rejected code is normalized to uppercase")
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
        runCurrent()
        assertTrue(vm.state.isInRoom)

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
        runCurrent()
        assertTrue(vm.state.isInRoom)

        vm.takeAction(LobbyAction.Leave)
        runCurrent()

        assertEquals(null, vm.state.room)
        assertEquals(ConnectionStatus.Disconnected, vm.state.connectionStatus)
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
        runCurrent()

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
        runCurrent()

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
        runCurrent()

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
        runCurrent()

        assertEquals(LobbyError.CreateNetworkError, vm.state.createError)
        assertFalse(vm.state.creating, "the spinner is gone once the create error lands")
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

    // ---------- host-only actions (StartGame / Add/RemoveBot) ----------

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
    fun soleMember_notYetConnected_isEffectiveHost() = runUnitTest {
        // The just-created host's presence flip can lag the first snapshot.
        // The fallback (first human when nobody reads connected) must mirror
        // the server's Room.effectiveHostUserId so buttons shown here are
        // never rejected there (ROOM-16).
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = false))),
            ),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()

        assertTrue(vm.state.isHost, "sole human wields host powers before presence flips")
    }

    @Test
    fun addBot_failure_emitsBotActionFailedEvent() = runUnitTest {
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true))),
            ),
            addBotOutcome = AddBotOutcome.NotHost,
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()

        vm.eventFlow.test {
            vm.takeAction(LobbyAction.AddBot(seatIndex = 1))
            assertIs<LobbyEvent.BotActionFailed>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun addBot_inFlight_marksSeatPending_andIgnoresRepeatTaps() = runUnitTest {
        // ROOM-18: while a seat's add-bot request is in flight the seat is
        // pending (drives the spinner) and a repeat tap on it fires no second
        // request.
        val gate = CompletableDeferred<AddBotOutcome>()
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true))),
            ),
            addBotGate = gate,
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()
        assertTrue(vm.state.isHost)

        vm.takeAction(LobbyAction.AddBot(seatIndex = 1))
        runCurrent()
        assertTrue(1 in vm.state.addingBotSeatIndexes, "seat is pending while the request is in flight")

        vm.takeAction(LobbyAction.AddBot(seatIndex = 1))
        runCurrent()
        assertEquals(listOf<Int?>(1), rooms.addBotSeatIndexes, "a repeat tap on a pending seat fires no second request")

        gate.complete(
            AddBotOutcome.Success(roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true)))),
        )
        runCurrent()
        assertFalse(1 in vm.state.addingBotSeatIndexes, "seat clears once the request settles")
    }

    @Test
    fun addBot_failure_clearsPendingSeat() = runUnitTest {
        // A failed add-bot request must release the seat so the host can retry.
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true))),
            ),
            addBotOutcome = AddBotOutcome.NetworkError(RuntimeException("offline")),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()

        vm.takeAction(LobbyAction.AddBot(seatIndex = 1))
        runCurrent()

        assertFalse(1 in vm.state.addingBotSeatIndexes, "a failed request releases the seat for retry")
    }

    @Test
    fun removeBot_host_callsRepositoryWithBotUserId() = runUnitTest {
        val rooms = RecordingRoomRepository(
            createOutcome = CreateRoomOutcome.Success(
                roomOf(members = listOf(member(LOCAL_USER, "You", isConnected = true))),
            ),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)
        runCurrent()
        assertTrue(vm.state.isHost)

        vm.takeAction(LobbyAction.RemoveBot(botUserId = "bot-1"))
        runCurrent()

        assertEquals(listOf("bot-1"), rooms.removedBotUserIds)
        assertNull(vm.state.error, "a successful removal surfaces no error")
    }

    @Test
    fun removeBot_nonHost_isNoOp() = runUnitTest {
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

        vm.takeAction(LobbyAction.RemoveBot(botUserId = "bot-1"))
        runCurrent()

        assertTrue(rooms.removedBotUserIds.isEmpty())
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
    fun effectiveHostUserId_noConnectedMembers_fallsBackToFirstHuman() = runUnitTest {
        // Presence flips can lag the snapshot (a just-joined member reads
        // disconnected for a beat) — rather than a hostless room, the first
        // human holds the powers. Mirrors the server's Room.effectiveHostUserId
        // (ROOM-16).
        val state = LobbyState(
            room = roomOf(
                members = listOf(
                    member("a", "A", isConnected = false),
                    member("b", "B", isConnected = false, seatIndex = 1),
                ),
            ),
        )
        assertEquals("a", state.effectiveHostUserId)
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
                visibility = RoomVisibility.Open,
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
                        visibility = RoomVisibility.Open,
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
        runCurrent()
        assertTrue(vm.state.isInRoom)

        vm.takeAction(LobbyAction.Leave)
        runCurrent()

        assertNull(vm.state.room)
        assertEquals(LobbyError.LeaveServerNotNotified, vm.state.error)
        assertEquals(ConnectionStatus.Disconnected, vm.state.connectionStatus)
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
        profile: ProfileRepository = NoProfileRepository,
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

    private val LOCAL_USER = LOBBY_TEST_LOCAL_USER

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
        visibility: RoomVisibility = RoomVisibility.Private,
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

    private fun sampleRoom(code: String = "ABC123") = Room(
        code = code,
        hostUserId = LOCAL_USER,
        createdAtEpochMs = 1_700_000_000_000,
        maxSeats = 4,
        status = RoomStatus.Lobby,
        members = listOf(
            RoomMember(
                userId = LOCAL_USER,
                displayName = "Host",
                seatIndex = 0,
                joinedAtEpochMs = 1_700_000_000_000,
                isConnected = false,
            ),
        ),
    )
}

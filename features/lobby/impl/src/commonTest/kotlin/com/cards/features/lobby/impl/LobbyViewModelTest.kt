package com.dangerfield.cards.features.lobby.impl

import app.cash.turbine.test
import androidx.lifecycle.viewModelScope
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
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
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
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    fun startGame_surfacesComingSoon() = runUnitTest {
        val vm = buildVm()
        vm.takeAction(LobbyAction.StartGame)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertEquals(LobbyError.StartGameComingSoon, last.error)
            cancelAndIgnoreRemainingEvents()
        }
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
            assertEquals("PREFIL", last.room!!.code)
            assertEquals(1, rooms.joinCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
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

        gate.complete(LeaveRoomOutcome.Success)
        runCurrent()
        assertEquals(1, rooms.leaveFinished, "leaveRoom must complete despite VM teardown")
    }

    @Test
    fun leave_returnsToIdle_andCancelsConnection() = runUnitTest {
        val room = sampleRoom()
        val rooms = FakeRoomRepository(
            createOutcome = CreateRoomOutcome.Success(room),
            leaveOutcome = LeaveRoomOutcome.Success,
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

    // ---------- scaffolding ----------

    private fun buildVm(
        rooms: RoomRepository = FakeRoomRepository(),
        identity: AuthRepository = AlwaysSignedInAuth(),
        prefilledCode: String? = null,
    ): LobbyViewModel = LobbyViewModel(
        prefilledCode = prefilledCode,
        rooms = rooms,
        auth = identity,
        appScope = AppCoroutineScope(dispatchers),
    )

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

        override suspend fun createRoom(maxSeats: Int?): CreateRoomOutcome = createOutcome
        override suspend fun joinRoom(code: String): JoinRoomOutcome =
            JoinRoomOutcome.NetworkError(RuntimeException("not used"))
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome {
            leaveStarted += 1
            val outcome = leaveGate.await()
            leaveFinished += 1
            return outcome
        }
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome =
            GetActiveRoomsOutcome.Success(emptyList())
        override fun observeRoom(code: String): Flow<RoomConnection> = flow { }
    }

    private class FakeRoomRepository(
        private val createOutcome: CreateRoomOutcome = CreateRoomOutcome.NetworkError(RuntimeException("simulated network error")),
        private val joinOutcome: JoinRoomOutcome = JoinRoomOutcome.NetworkError(RuntimeException("simulated network error")),
        private val leaveOutcome: LeaveRoomOutcome = LeaveRoomOutcome.Success,
        private val activeRoomsOutcome: GetActiveRoomsOutcome = GetActiveRoomsOutcome.Success(emptyList()),
        private val observe: (String) -> Flow<RoomConnection> = { flow {} },
    ) : RoomRepository {
        var joinCalls: Int = 0
            private set
        override suspend fun createRoom(maxSeats: Int?): CreateRoomOutcome = createOutcome
        override suspend fun joinRoom(code: String): JoinRoomOutcome {
            joinCalls += 1
            return joinOutcome
        }
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = leaveOutcome
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome = activeRoomsOutcome
        override fun observeRoom(code: String): Flow<RoomConnection> = observe(code)
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

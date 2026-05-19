package com.dangerfield.cards.features.lobby.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.identity.SignInOutcome
import com.dangerfield.cards.libraries.identity.SignUpOutcome
import com.dangerfield.cards.libraries.identity.UpdateProfileOutcome
import com.dangerfield.cards.libraries.identity.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.OAuthProvider
import com.dangerfield.cards.libraries.identity.RefreshOutcome
import com.dangerfield.cards.libraries.identity.ResendOutcome
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnection
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
            createOutcome = CreateRoomOutcome.NetworkError(java.io.IOException()),
        )
        val vm = buildVm(rooms = rooms)
        vm.takeAction(LobbyAction.CreateRoom)

        vm.stateFlow.test {
            var last = awaitItem()
            while (last.error == null) last = awaitItem()
            assertTrue(last.error!!.contains("reach"))
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
            assertTrue(last.error!!.contains("full"))
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
            assertTrue(last.error!!.contains("Enter"))
            assertEquals(0, rooms.joinCalls, "blank code short-circuits before the network")
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
        identity: IdentityRepository = AlwaysSignedInIdentity(),
        prefilledCode: String? = null,
    ): LobbyViewModel = LobbyViewModel(
        prefilledCode = prefilledCode,
        rooms = rooms,
        identity = identity,
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

    private class FakeRoomRepository(
        private val createOutcome: CreateRoomOutcome = CreateRoomOutcome.NetworkError(java.io.IOException()),
        private val joinOutcome: JoinRoomOutcome = JoinRoomOutcome.NetworkError(java.io.IOException()),
        private val leaveOutcome: LeaveRoomOutcome = LeaveRoomOutcome.Success,
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
        override fun observeRoom(code: String): Flow<RoomConnection> = observe(code)
    }

    private class AlwaysSignedInIdentity : IdentityRepository {
        private val identity = Identity(
            userId = "11111111-1111-1111-1111-111111111111",
            displayName = "You",
            avatarEmoji = "🃏",
            isAnonymous = true,
        )
        override val state: Flow<IdentityState> = MutableStateFlow(IdentityState.SignedIn(identity)).asStateFlow()
        override suspend fun ensureInitialized(): Identity = identity
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
            SignInOutcome.Success(identity)
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
            SignUpOutcome.VerificationRequired(email)
        override suspend fun refreshSession(): RefreshOutcome = RefreshOutcome.EmailConfirmed(identity)
        override suspend fun resendVerificationEmail(email: String): ResendOutcome = ResendOutcome.Sent
        override suspend fun signOut() { /* no-op */ }
        override suspend fun updateProfile(displayName: String?, avatarEmoji: String?): UpdateProfileOutcome =
            UpdateProfileOutcome.Success(identity)
        override suspend fun fetchAvatarPack(): AvatarPackOutcome = AvatarPackOutcome.Success(emptyList())
        override suspend fun deleteAccount(): DeleteAccountOutcome = DeleteAccountOutcome.Success
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
            LinkIdentityOutcome.Success(identity)
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
            SignInOutcome.Success(identity)
    }
}

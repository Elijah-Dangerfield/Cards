package com.dangerfield.cards.features.lobby.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.rooms.AddBotOutcome
import com.dangerfield.cards.libraries.rooms.GetActiveRoomsOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.LeaveRoomOutcome
import com.dangerfield.cards.libraries.rooms.RemoveBotOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomConnectionHandle
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomRepository
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.RoomVisibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROOM-5: a bad code must surface an inline error on the PrivateJoin screen with
 * NO navigation — the join is validated in place. Only a successful join emits a
 * navigate-to-lobby event.
 */
class PrivateJoinViewModelTest : CoroutineTest() {

    @Test
    fun `bad code surfaces inline error and emits no navigation`() = runUnitTest {
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.NotFound)
        val vm = PrivateJoinViewModel(rejectedCode = null, rooms = rooms, profile = AuthedProfileRepository)

        vm.eventFlow.test {
            vm.takeAction(PrivateJoinAction.CodeChanged("wxyz12"))
            vm.takeAction(PrivateJoinAction.Submit)
            expectNoEvents()
        }

        val error = assertIs<PrivateJoinError.RoomNotFound>(vm.state.error)
        assertEquals("WXYZ12", error.code)
        assertEquals(false, vm.state.joining)
        assertEquals(1, rooms.joinCalls)
    }

    @Test
    fun `successful join emits navigate-to-lobby with the code`() = runUnitTest {
        val rooms = FakeRoomRepository(
            joinOutcome = JoinRoomOutcome.Success(room = sampleRoom("ABC123"), alreadyJoined = false),
        )
        val vm = PrivateJoinViewModel(rejectedCode = null, rooms = rooms, profile = AuthedProfileRepository)

        vm.eventFlow.test {
            vm.takeAction(PrivateJoinAction.CodeChanged("abc123"))
            vm.takeAction(PrivateJoinAction.Submit)
            val event = assertIs<PrivateJoinEvent.NavigateToLobby>(awaitItem())
            assertEquals("ABC123", event.code)
        }
        assertNull(vm.state.error)
    }

    @Test
    fun `rejected deep-link code seeds the field and inline error without rejoining`() = runUnitTest {
        val rooms = FakeRoomRepository(joinOutcome = JoinRoomOutcome.NotFound)
        val vm = PrivateJoinViewModel(rejectedCode = "gffddf", rooms = rooms, profile = AuthedProfileRepository)

        assertEquals("GFFDDF", vm.state.code)
        val error = assertIs<PrivateJoinError.RoomNotFound>(vm.state.error)
        assertEquals("GFFDDF", error.code)
        assertEquals(0, rooms.joinCalls)
    }

    @Test
    fun `code is uppercased and capped at six chars`() = runUnitTest {
        val vm = PrivateJoinViewModel(rejectedCode = null, rooms = FakeRoomRepository(), profile = AuthedProfileRepository)
        vm.takeAction(PrivateJoinAction.CodeChanged("abcd1234"))
        assertEquals("ABCD12", vm.state.code)
        assertTrue(vm.state.canSubmit)
    }

    private fun sampleRoom(code: String) = Room(
        code = code,
        hostUserId = "host",
        createdAtEpochMs = 1_700_000_000_000,
        maxSeats = 4,
        status = RoomStatus.Lobby,
        members = listOf(
            RoomMember(
                userId = "host",
                displayName = "Host",
                seatIndex = 0,
                joinedAtEpochMs = 1_700_000_000_000,
                isConnected = true,
            ),
        ),
        visibility = RoomVisibility.Private,
    )

    private object AuthedProfileRepository : ProfileRepository {
        private val profile = Profile.Authenticated(
            id = "11111111-1111-1111-1111-111111111111",
            displayName = "You",
            avatarEmoji = "🃏",
            avatarBackgroundColor = null,
            email = null,
            isAnonymous = true,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(1_700_000_000_000),
        )
        override suspend fun current() = profile
        override fun observe() = MutableStateFlow<Profile>(profile).asStateFlow()
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ) = error("not used")
        override suspend fun fetchAvatarPack() = error("not used")
    }

    private class FakeRoomRepository(
        private val joinOutcome: JoinRoomOutcome =
            JoinRoomOutcome.NetworkError(RuntimeException("not used")),
    ) : RoomRepository {
        var joinCalls: Int = 0
            private set

        override suspend fun createRoom(maxSeats: Int?, buyIn: Long?, open: Boolean) =
            error("not used")
        override suspend fun joinRoom(code: String): JoinRoomOutcome {
            joinCalls += 1
            return joinOutcome
        }
        override suspend fun leaveRoom(code: String): LeaveRoomOutcome = LeaveRoomOutcome.Success
        override suspend fun addBot(code: String, seatIndex: Int?): AddBotOutcome = error("not used")
        override suspend fun removeBot(code: String, botUserId: String): RemoveBotOutcome =
            RemoveBotOutcome.Success
        override suspend fun getActiveRooms(): GetActiveRoomsOutcome =
            GetActiveRoomsOutcome.Success(emptyList())
        override fun observeActiveRooms(): Flow<List<Room>> = flow { }
        override fun connect(code: String): RoomConnectionHandle = error("not used")
    }
}

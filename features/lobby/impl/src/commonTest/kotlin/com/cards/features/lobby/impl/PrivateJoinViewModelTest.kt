package com.dangerfield.cards.features.lobby.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.RoomVisibility
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
        val rooms = CountingJoinRepository(joinOutcome = JoinRoomOutcome.NotFound)
        val vm = PrivateJoinViewModel(rejectedCode = null, rooms = rooms)

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
        val rooms = CountingJoinRepository(
            joinOutcome = JoinRoomOutcome.Success(room = sampleRoom("ABC123"), alreadyJoined = false),
        )
        val vm = PrivateJoinViewModel(rejectedCode = null, rooms = rooms)

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
        val rooms = CountingJoinRepository(joinOutcome = JoinRoomOutcome.NotFound)
        val vm = PrivateJoinViewModel(rejectedCode = "gffddf", rooms = rooms)

        assertEquals("GFFDDF", vm.state.code)
        val error = assertIs<PrivateJoinError.RoomNotFound>(vm.state.error)
        assertEquals("GFFDDF", error.code)
        assertEquals(0, rooms.joinCalls)
    }

    @Test
    fun `code is uppercased and capped at six chars`() = runUnitTest {
        val vm = PrivateJoinViewModel(rejectedCode = null, rooms = CountingJoinRepository())
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

    /** The join screen only ever calls [joinRoom]; everything else fails loudly via the stub. */
    private class CountingJoinRepository(
        private val joinOutcome: JoinRoomOutcome =
            JoinRoomOutcome.NetworkError(RuntimeException("not used")),
    ) : StubRoomRepository() {
        var joinCalls: Int = 0
            private set

        override suspend fun joinRoom(code: String): JoinRoomOutcome {
            joinCalls += 1
            return joinOutcome
        }
    }
}

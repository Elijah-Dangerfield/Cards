package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.LeaveResult
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins [InMemoryRoomService] semantics — the contract every other room
 * surface depends on. WebSocket presence + HTTP routes both go through
 * this service, so concurrency + correctness here is load-bearing.
 *
 * What we pin:
 *  - create() returns a unique 6-char unambiguous-alphabet code, seats
 *    the host in seat 0, status = Lobby.
 *  - join() is idempotent on the same userId — rejoining returns the
 *    existing seat, not a duplicate.
 *  - join() fills the lowest free seat, so leave+rejoin gets a stable
 *    chart.
 *  - join() respects maxSeats (returns Full after capacity).
 *  - leave() removes the member; when the last one leaves the room is
 *    GC'd (Success(roomGone=true)) and subsequent find() returns null.
 *  - markConnected() flips a member's presence without otherwise
 *    touching the seating chart.
 *  - observe() emits the current room on subscribe + on every mutation.
 *  - Concurrent joins from N callers all land cleanly (no torn state,
 *    no duplicate seats).
 */
@OptIn(ExperimentalTime::class)
class InMemoryRoomServiceTest {

    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val bob = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))

    @Test
    fun create_returnsRoomInLobby_withHostInSeat0() = runTest {
        val service = newService()
        val room = service.create(host, hostName = "Host", maxSeats = 4)
        assertEquals(RoomStatus.Lobby, room.status)
        assertEquals(4, room.maxSeats)
        assertEquals(host, room.hostUserId)
        assertEquals(1, room.members.size)
        val hostMember = room.members.single()
        assertEquals(host, hostMember.userId)
        assertEquals(0, hostMember.seatIndex)
        assertEquals("Host", hostMember.displayName)
        assertEquals(false, hostMember.isConnected, "fresh-created host's socket isn't open yet")
    }

    @Test
    fun create_codeMatchesAlphabet_andIsCorrectLength() = runTest {
        val service = newService()
        val room = service.create(host, "Host")
        assertEquals(InMemoryRoomService.CODE_LENGTH, room.code.length)
        assertTrue(
            room.code.all { it in InMemoryRoomService.CODE_ALPHABET },
            "code '${room.code}' had a char outside the unambiguous alphabet",
        )
    }

    @Test
    fun join_isIdempotent_forSameUser() = runTest {
        val service = newService()
        val room = service.create(host, "Host")

        val first = service.join(room.code, alice, "Alice")
        val second = service.join(room.code, alice, "Alice")

        assertIs<JoinResult.Success>(first)
        val redundant = assertIs<JoinResult.AlreadyJoined>(second)
        assertEquals(2, redundant.room.members.size, "rejoin didn't add a duplicate member")
        assertEquals(
            first.room.memberFor(alice)!!.seatIndex,
            redundant.room.memberFor(alice)!!.seatIndex,
            "rejoin kept the original seat",
        )
    }

    @Test
    fun join_seatsInLowestFreeIndex_afterMidRoomLeave() = runTest {
        val service = newService()
        val room = service.create(host, "Host", maxSeats = 4)
        service.join(room.code, alice, "Alice") // seat 1
        service.join(room.code, bob, "Bob")     // seat 2

        service.leave(room.code, alice)         // frees seat 1
        val charlie = UserId(UUID.randomUUID())
        val outcome = service.join(room.code, charlie, "Charlie")

        val success = assertIs<JoinResult.Success>(outcome)
        assertEquals(
            1,
            success.room.memberFor(charlie)!!.seatIndex,
            "joiner took the freed lowest seat, not the next one after Bob",
        )
    }

    @Test
    fun join_returnsFull_atCapacity() = runTest {
        val service = newService()
        val room = service.create(host, "Host", maxSeats = 2)
        service.join(room.code, alice, "Alice")
        val outcome = service.join(room.code, bob, "Bob")
        assertIs<JoinResult.Full>(outcome)
    }

    @Test
    fun join_unknownCode_returnsNotFound() = runTest {
        val service = newService()
        val outcome = service.join("ABCDEF", alice, "Alice")
        assertIs<JoinResult.RoomNotFound>(outcome)
    }

    @Test
    fun leave_lastMember_reapsRoom() = runTest {
        val service = newService()
        val room = service.create(host, "Host")
        val outcome = service.leave(room.code, host)
        val success = assertIs<LeaveResult.Success>(outcome)
        assertTrue(success.roomGone)
        assertNull(service.find(room.code), "GC'd room is gone from find()")
    }

    @Test
    fun leave_nonHost_keepsRoomAlive() = runTest {
        val service = newService()
        val room = service.create(host, "Host")
        service.join(room.code, alice, "Alice")

        val outcome = service.leave(room.code, alice)
        val success = assertIs<LeaveResult.Success>(outcome)
        assertEquals(false, success.roomGone)
        assertNotNull(service.find(room.code), "host still in room — keep it alive")
        assertEquals(1, service.find(room.code)!!.members.size)
    }

    @Test
    fun leave_nonMember_returnsNotInRoom() = runTest {
        val service = newService()
        val room = service.create(host, "Host")
        val outcome = service.leave(room.code, alice)
        assertIs<LeaveResult.NotInRoom>(outcome)
    }

    @Test
    fun markConnected_flipsMemberPresence() = runTest {
        val service = newService()
        val room = service.create(host, "Host")
        assertEquals(false, service.find(room.code)!!.memberFor(host)!!.isConnected)

        service.markConnected(room.code, host, connected = true)
        assertEquals(true, service.find(room.code)!!.memberFor(host)!!.isConnected)

        service.markConnected(room.code, host, connected = false)
        assertEquals(false, service.find(room.code)!!.memberFor(host)!!.isConnected)
    }

    @Test
    fun observe_emitsCurrentRoom_onSubscribe_andOnEveryMutation() = runTest {
        val service = newService()
        val room = service.create(host, "Host", maxSeats = 4)
        val flow = service.observe(room.code)!!

        // Subscribe-time read — StateFlow's first() is immediate.
        val initial = flow.first()
        assertEquals(1, initial.members.size)

        // Mutation propagates.
        service.join(room.code, alice, "Alice")
        val afterJoin = flow.first()
        assertEquals(2, afterJoin.members.size)
    }

    @Test
    fun concurrentJoins_neverDuplicateSeats() = runTest {
        val service = newService()
        val room = service.create(host, "Host", maxSeats = 6)
        val joiners = (1..5).map { i -> UserId(UUID.randomUUID()) to "P$i" }
        // Fire all five joins concurrently. The mutex should serialize
        // them; the resulting seats must all be unique and in [1, 5].
        val results = joiners.map { (id, name) ->
            async { service.join(room.code, id, name) }
        }.awaitAll()

        results.forEach { assertIs<JoinResult.Success>(it) }
        val final = service.find(room.code)!!
        assertEquals(6, final.members.size, "host + 5 joiners")
        val seatIndices = final.members.map { it.seatIndex }
        assertEquals(seatIndices.size, seatIndices.toSet().size, "seat indices must be unique")
        assertEquals(listOf(0, 1, 2, 3, 4, 5), seatIndices.sorted(), "seats fill 0..5")
    }

    // ---------- scaffolding ----------

    private fun newService(seed: Long = 0L): InMemoryRoomService = InMemoryRoomService(
        clock = FixedClock(),
        random = Random(seed),
    )

    private class FixedClock(private val ms: Long = 1_700_000_000_000) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
    }

}

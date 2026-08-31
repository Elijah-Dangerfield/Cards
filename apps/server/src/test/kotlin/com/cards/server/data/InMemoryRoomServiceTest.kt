package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.server.domain.AddBotResult
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.LeaveResult
import com.dangerfield.cards.server.domain.MatchmakingResult
import com.dangerfield.cards.server.domain.RemoveBotResult
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.RoomVisibility
import com.dangerfield.cards.server.domain.SYSTEM_HOST_USER_ID
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
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
    private val carol = UserId(UUID.fromString("44444444-4444-4444-4444-444444444444"))

    @Test
    fun create_returnsRoomInLobby_withHostInSeat0() = runTest {
        val service = newService()
        val room = service.createOrFail(host, hostName = "Host", maxSeats = 4)
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
    fun join_hydratesFromTheDurableStore_whenTheProcessRestarted() = runTest {
        val store = FakeRoomStore()
        val beforeRestart = InMemoryRoomService(FixedClock(), Random(0L), store)
        val code = beforeRestart.createOrFail(host, "Host").code

        // Fresh process: the durable rows survive, the in-memory map doesn't. The
        // invited friend is the first to touch the room, so nothing has hydrated it.
        val afterRestart = InMemoryRoomService(FixedClock(), Random(1L), store)
        val result = afterRestart.join(code, alice, "Alice")

        assertIs<JoinResult.Success>(result)
        assertEquals(
            setOf(host, alice),
            result.room.members.map { it.userId }.toSet(),
            "the joiner lands in the persisted room alongside its host",
        )
    }

    @Test
    fun create_codeMatchesAlphabet_andIsCorrectLength() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        assertEquals(InMemoryRoomService.CODE_LENGTH, room.code.length)
        assertTrue(
            room.code.all { it in InMemoryRoomService.CODE_ALPHABET },
            "code '${room.code}' had a char outside the unambiguous alphabet",
        )
    }

    @Test
    fun join_isIdempotent_forSameUser() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")

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
        val room = service.createOrFail(host, "Host", maxSeats = 4)
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
        val room = service.createOrFail(host, "Host", maxSeats = 2)
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
    fun join_whilePlaying_seatsMember_forMidHandJoin() = runTest {
        // Private rooms accept code-share joins while a hand is in flight —
        // the joiner takes a seat slot now and the next-hand seating logic
        // (queueMidHandJoinerIfNeeded on socket connect) deals them in at the
        // next boundary. Previously this returned NotJoinable(Playing), which
        // forced friends to wait outside a live game when they had the invite.
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.markPlaying(room.code)

        val outcome = service.join(room.code, alice, "Alice")

        val success = assertIs<JoinResult.Success>(outcome)
        assertEquals(2, success.room.members.size, "joiner seated alongside the host")
        assertEquals(RoomStatus.Playing, success.room.status, "join must not flip the live hand back to Lobby")
        assertNotNull(success.room.memberFor(alice))
    }

    @Test
    fun join_afterMatchOver_isRejectedAsNotJoinable() = runTest {
        // markFinished now flips a room to the terminal Finished status (the
        // heads-up match-over resolution, MP-14) — not back to Lobby. A finished
        // match is over, so a late joiner is turned away rather than seated into a
        // dead table. (Joining the lull *between* hands stays a Playing-status join,
        // covered by the mid-hand-join test.)
        val service = newService()
        val room = service.createOrFail(host, "Host")
        service.markPlaying(room.code)
        service.markFinished(room.code)

        val outcome = service.join(room.code, alice, "Alice")

        assertIs<JoinResult.NotJoinable>(outcome)
        assertEquals(RoomStatus.Finished, service.find(room.code)!!.status)
    }

    @Test
    fun leave_lastMember_reapsRoom() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        val outcome = service.leave(room.code, host)
        val success = assertIs<LeaveResult.Success>(outcome)
        assertTrue(success.roomGone)
        assertNull(service.find(room.code), "GC'd room is gone from find()")
    }

    @Test
    fun leave_nonHost_keepsRoomAlive() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
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
        val room = service.createOrFail(host, "Host")
        val outcome = service.leave(room.code, alice)
        assertIs<LeaveResult.NotInRoom>(outcome)
    }

    @Test
    fun leave_host_promotesLongestTenuredRemainingMember() = runTest {
        // Host leaves a room with two other members. With FixedClock all
        // joins share the same instant, so the tiebreak falls to seatIndex —
        // Alice took seat 1, Bob took seat 2, so Alice becomes host. Either
        // way (timestamp or seat), it's the longest-tenured remaining
        // member; the test pins both via the AND in the comparator. The
        // pre-fix behavior left hostUserId pointing at the departed host,
        // which would have broken any host-only affordance (start-game).
        val service = newService()
        val room = service.createOrFail(host, "Host")
        service.join(room.code, alice, "Alice")
        service.join(room.code, bob, "Bob")

        val outcome = service.leave(room.code, host)
        assertIs<LeaveResult.Success>(outcome)
        val after = service.find(room.code)
        assertNotNull(after)
        assertEquals(alice, after.hostUserId, "longest-tenured remaining member should become host")
        assertEquals(2, after.members.size)
        assertEquals(setOf(alice, bob), after.members.map { it.userId }.toSet())
    }

    @Test
    fun leave_nonHost_doesNotChangeHost() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        service.join(room.code, alice, "Alice")

        service.leave(room.code, alice)
        val after = service.find(room.code)
        assertNotNull(after)
        assertEquals(host, after.hostUserId, "non-host leaving must not reshuffle the host pointer")
    }

    @Test
    fun markConnected_flipsMemberPresence() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        assertEquals(false, service.find(room.code)!!.memberFor(host)!!.isConnected)

        service.markConnected(room.code, host, connected = true)
        assertEquals(true, service.find(room.code)!!.memberFor(host)!!.isConnected)

        service.markConnected(room.code, host, connected = false)
        assertEquals(false, service.find(room.code)!!.memberFor(host)!!.isConnected)
    }

    @Test
    fun openSocketConnection_marksConnected_andReturnsMonotonicId() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")

        val first = service.openSocketConnection(room.code, host)
        val second = service.openSocketConnection(room.code, host)
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(second > first, "each open bumps the connection id")
        val member = service.find(room.code)!!.memberFor(host)!!
        assertTrue(member.isConnected)
        assertNull(member.disconnectedAt, "opening a socket clears the seat-grace stamp")
    }

    @Test
    fun openSocketConnection_forNonMember_returnsNull() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        // alice never joined — no seat, so there's no presence to open.
        assertNull(service.openSocketConnection(room.code, alice))
    }

    @Test
    fun closeSocketConnection_currentId_disconnectsAndStampsGrace() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        val id = service.openSocketConnection(room.code, host)!!

        val after = service.closeSocketConnectionIfCurrent(room.code, host, id)
        assertNotNull(after, "closing the current socket takes effect")
        val member = after.memberFor(host)!!
        assertEquals(false, member.isConnected)
        assertNotNull(member.disconnectedAt, "a real close arms the grace reaper")
    }

    @Test
    fun closeSocketConnection_supersededId_isIgnored_soFastReconnectKeepsSeatLive() = runTest {
        // The reconnect race the prod-deploy test flake exposed: a member opens a
        // second socket before the first socket's server-side teardown runs. The
        // stale close must NOT disconnect the live member or arm a reaper.
        val service = newService()
        val room = service.createOrFail(host, "Host")
        service.join(room.code, alice, "Alice")

        val socketA = service.openSocketConnection(room.code, alice)!!
        val socketB = service.openSocketConnection(room.code, alice)!! // reconnect, before A tears down

        // A's late teardown carries the now-superseded id → no-op.
        val afterStale = service.closeSocketConnectionIfCurrent(room.code, alice, socketA)
        assertNull(afterStale, "a superseded socket's close is ignored")
        val liveMember = service.find(room.code)!!.memberFor(alice)!!
        assertTrue(liveMember.isConnected, "the member stays connected on socket B")
        assertNull(liveMember.disconnectedAt, "no grace stamp — nothing to reap")

        // B's own teardown (the current id) still disconnects normally.
        val afterReal = service.closeSocketConnectionIfCurrent(room.code, alice, socketB)
        assertNotNull(afterReal)
        assertEquals(false, afterReal.memberFor(alice)!!.isConnected)
    }

    @Test
    fun observe_emitsCurrentRoom_onSubscribe_andOnEveryMutation() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 4)
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
        val room = service.createOrFail(host, "Host", maxSeats = 6)
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

    // ---------- sweepDisconnected ----------

    @Test
    fun markConnected_false_stampsDisconnectedAt() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        service.markConnected(room.code, host, connected = true)

        val before = clock.now()
        clock.advance(1.minutes)
        service.markConnected(room.code, host, connected = false)

        val member = service.find(room.code)!!.memberFor(host)!!
        assertEquals(false, member.isConnected)
        assertNotNull(member.disconnectedAt)
        assertTrue(
            member.disconnectedAt >= before,
            "disconnectedAt stamped at-or-after the disconnect",
        )
    }

    @Test
    fun markConnected_true_clearsDisconnectedAt() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        // create() stamps disconnectedAt = now so the seat-grace clock
        // ticks even for never-connected members.
        assertNotNull(service.find(room.code)!!.memberFor(host)!!.disconnectedAt)

        clock.advance(1.minutes)
        service.markConnected(room.code, host, connected = true)
        assertNull(
            service.find(room.code)!!.memberFor(host)!!.disconnectedAt,
            "reconnect clears the grace timer",
        )
    }

    @Test
    fun sweepDisconnected_reapsMembersPastTtl() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.markConnected(room.code, host, connected = true)
        service.join(room.code, alice, "Alice")
        service.markConnected(room.code, alice, connected = true)

        // Alice drops. Sweep at TTL boundary should not reap her yet.
        service.markConnected(room.code, alice, connected = false)
        clock.advance(2.minutes)
        var result = service.sweepDisconnected(maxIdle = 5.minutes)
        assertEquals(0, result.membersReaped, "still within grace window")
        assertEquals(2, service.find(room.code)!!.members.size)

        // Past TTL — Alice is reaped, room survives (host still in).
        clock.advance(4.minutes)
        result = service.sweepDisconnected(maxIdle = 5.minutes)
        assertEquals(1, result.membersReaped)
        assertEquals(0, result.roomsReaped)
        val survivors = service.find(room.code)!!.members
        assertEquals(1, survivors.size)
        assertEquals(host, survivors.single().userId)
    }

    @Test
    fun sweepDisconnected_preservesConnectedMembers_regardlessOfClock() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        service.markConnected(room.code, host, connected = true)

        clock.advance(60.minutes)
        val result = service.sweepDisconnected(maxIdle = 1.minutes)
        assertEquals(0, result.membersReaped, "connected members never reaped")
        assertEquals(host, service.find(room.code)!!.members.single().userId)
    }

    @Test
    fun sweepDisconnected_neverConnectedMember_isReapedAfterTtl() = runTest {
        // Joins-but-never-opens-a-socket is the same shape as a clean drop;
        // both stamp disconnectedAt at the moment-of-join. This pins that
        // an abandoned join doesn't camp a seat forever.
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        service.markConnected(room.code, host, connected = true)
        service.join(room.code, alice, "Alice")
        // alice never calls markConnected.

        clock.advance(10.minutes)
        val result = service.sweepDisconnected(maxIdle = 5.minutes)
        assertEquals(1, result.membersReaped)
        assertNull(service.find(room.code)!!.memberFor(alice), "alice's seat freed")
    }

    @Test
    fun sweepDisconnected_emptiedRoom_isGCd() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        // Host never opens socket; create() stamped them.

        clock.advance(10.minutes)
        val result = service.sweepDisconnected(maxIdle = 5.minutes)
        assertEquals(1, result.membersReaped)
        assertEquals(1, result.roomsReaped)
        assertNull(service.find(room.code), "empty room GC'd same as last-out leave")
    }

    @Test
    fun sweepDisconnected_isIdempotent() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        service.markConnected(room.code, host, connected = true)
        service.join(room.code, alice, "Alice")
        service.markConnected(room.code, alice, connected = false)
        clock.advance(10.minutes)

        val first = service.sweepDisconnected(maxIdle = 5.minutes)
        val second = service.sweepDisconnected(maxIdle = 5.minutes)
        assertEquals(1, first.membersReaped)
        assertEquals(0, second.membersReaped, "second pass finds nothing left to reap")
    }

    @Test
    fun sweepDisconnected_reportsRoomsSeen() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val r1 = service.createOrFail(host, "Host A")
        service.markConnected(r1.code, host, connected = true)
        val r2 = service.createOrFail(alice, "Host B")
        service.markConnected(r2.code, alice, connected = true)
        assertNotEquals(r1.code, r2.code, "distinct codes — sanity")

        val result = service.sweepDisconnected(maxIdle = 5.minutes)
        assertEquals(2, result.roomsSeen, "both rooms inspected")
        assertEquals(0, result.membersReaped, "everybody still connected")
    }

    // ---------- create cap + snapshot ----------

    @Test
    fun create_rejectsHostBeyondMaxRoomsPerHost() = runTest {
        val service = newService()
        // First MAX_ROOMS_PER_HOST creates all succeed.
        repeat(RoomService.MAX_ROOMS_PER_HOST) {
            val outcome = service.create(host, "Host")
            assertIs<CreateResult.Success>(outcome)
        }
        // One past the cap fails — surfaces the count so the caller can
        // be specific in their error copy.
        val refused = service.create(host, "Host")
        val tooMany = assertIs<CreateResult.TooManyRooms>(refused)
        assertEquals(RoomService.MAX_ROOMS_PER_HOST, tooMany.activeCount)
    }

    @Test
    fun create_capIsPerHost_otherUsersUnaffected() = runTest {
        val service = newService()
        repeat(RoomService.MAX_ROOMS_PER_HOST) {
            assertIs<CreateResult.Success>(service.create(host, "Host"))
        }
        // A different user can still create — cap is per-user, not global.
        assertIs<CreateResult.Success>(service.create(alice, "Alice"))
    }

    @Test
    fun create_capIsReclaimable_afterLeavingARoom() = runTest {
        val service = newService()
        val first = assertIs<CreateResult.Success>(service.create(host, "Host"))
        repeat(RoomService.MAX_ROOMS_PER_HOST - 1) {
            assertIs<CreateResult.Success>(service.create(host, "Host"))
        }
        // At cap.
        assertIs<CreateResult.TooManyRooms>(service.create(host, "Host"))

        // Leave one — it was a solo room so the room itself gets GC'd
        // and the host frees a slot for a new create.
        service.leave(first.room.code, host)
        assertIs<CreateResult.Success>(service.create(host, "Host"))
    }

    @Test
    fun create_capIsReclaimable_afterSweep() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        // Three rooms abandoned right at create — the host never opens a
        // socket. After the sweep TTL elapses, all three get GC'd and the
        // slot opens up.
        repeat(RoomService.MAX_ROOMS_PER_HOST) {
            assertIs<CreateResult.Success>(service.create(host, "Host"))
        }
        assertIs<CreateResult.TooManyRooms>(service.create(host, "Host"))

        clock.advance(10.minutes)
        val swept = service.sweepDisconnected(maxIdle = 5.minutes)
        assertEquals(RoomService.MAX_ROOMS_PER_HOST, swept.roomsReaped)
        assertIs<CreateResult.Success>(service.create(host, "Host"))
    }

    @Test
    fun snapshot_listsLiveRoomsInStableOrder_andIsEmptyAfterSweep() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val r1 = assertIs<CreateResult.Success>(service.create(host, "A"))
        val r2 = assertIs<CreateResult.Success>(service.create(alice, "B"))

        val initial = service.snapshot()
        assertEquals(2, initial.size)
        // Same instant from FixedClock-style stepping — ties break on code,
        // so the order is fully deterministic for ops scans.
        assertEquals(initial.sortedBy { it.code }, initial)

        // Drain everyone — last-out reaps the room.
        service.leave(r1.room.code, host)
        service.leave(r2.room.code, alice)
        assertEquals(emptyList(), service.snapshot())
    }

    // ---------- reapIfStillDisconnected ----------

    @Test
    fun reapIfStillDisconnected_reapsMatchingStamp() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.markConnected(room.code, host, connected = true)
        service.join(room.code, alice, "Alice")
        service.markConnected(room.code, alice, connected = true)
        service.markConnected(room.code, alice, connected = false)
        val stamp = service.find(room.code)!!.memberFor(alice)!!.disconnectedAt!!

        val reaped = service.reapIfStillDisconnected(room.code, alice, stamp)
        assertTrue(reaped, "matching stamp on a still-disconnected member reaps")
        assertNull(service.find(room.code)!!.memberFor(alice), "alice's seat freed")
    }

    @Test
    fun reapIfStillDisconnected_skipsAfterReconnect() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.markConnected(room.code, host, connected = true)
        service.join(room.code, alice, "Alice")
        service.markConnected(room.code, alice, connected = true)
        service.markConnected(room.code, alice, connected = false)
        val firstStamp = service.find(room.code)!!.memberFor(alice)!!.disconnectedAt!!

        // She reconnects before the reaper fires — stale stamp must no-op.
        service.markConnected(room.code, alice, connected = true)
        val reaped = service.reapIfStillDisconnected(room.code, alice, firstStamp)
        assertEquals(false, reaped, "stale stamp must not reap a reconnected member")
        assertEquals(2, service.find(room.code)!!.members.size, "alice is still seated")
    }

    @Test
    fun reapIfStillDisconnected_skipsAfterRedisconnect() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.markConnected(room.code, host, connected = true)
        service.join(room.code, alice, "Alice")
        service.markConnected(room.code, alice, connected = true)
        service.markConnected(room.code, alice, connected = false)
        val firstStamp = service.find(room.code)!!.memberFor(alice)!!.disconnectedAt!!

        // She bounces — reconnect, then re-drop with a fresh stamp.
        service.markConnected(room.code, alice, connected = true)
        clock.advance(1.minutes)
        service.markConnected(room.code, alice, connected = false)
        val secondStamp = service.find(room.code)!!.memberFor(alice)!!.disconnectedAt!!
        assertNotEquals(firstStamp, secondStamp, "fresh disconnect stamps a new time")

        // The original reaper (carrying firstStamp) is now stale.
        assertEquals(false, service.reapIfStillDisconnected(room.code, alice, firstStamp))
        assertEquals(2, service.find(room.code)!!.members.size)
        // A reaper scheduled for the second stamp does its job.
        assertTrue(service.reapIfStillDisconnected(room.code, alice, secondStamp))
        assertNull(service.find(room.code)!!.memberFor(alice))
    }

    @Test
    fun reapIfStillDisconnected_emptiedRoom_isGCd() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        // create() stamps disconnectedAt = now on the host.
        val stamp = service.find(room.code)!!.memberFor(host)!!.disconnectedAt!!

        val reaped = service.reapIfStillDisconnected(room.code, host, stamp)
        assertTrue(reaped)
        assertNull(service.find(room.code), "last-out reap GCs the room same as leave()")
    }

    @Test
    fun reapIfStillDisconnected_unknownRoomOrMember_isNoop() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        val stamp = service.find(room.code)!!.memberFor(host)!!.disconnectedAt!!

        assertEquals(false, service.reapIfStillDisconnected("ZZZZZZ", host, stamp))
        assertEquals(false, service.reapIfStillDisconnected(room.code, alice, stamp))
        assertNotNull(service.find(room.code), "no-op leaves the room alone")
    }

    @Test
    fun sweepDisconnected_emitsRoomUpdate_throughObserveFlow() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = service.createOrFail(host, "Host")
        service.markConnected(room.code, host, connected = true)
        service.join(room.code, alice, "Alice")
        service.markConnected(room.code, alice, connected = false)
        clock.advance(10.minutes)

        // Observe before the sweep: snapshot has 2 members.
        val flow = service.observe(room.code)!!
        assertEquals(2, flow.first().members.size)

        service.sweepDisconnected(maxIdle = 5.minutes)
        // Sweep mutated state through the flow — subscribers see 1.
        assertEquals(1, flow.first().members.size)
    }

    @Test
    fun create_and_join_pruneTheReservedBotAvatar_soHumansCantImpersonateBots() = runTest {
        val service = newService()

        // A human host wearing the reserved bot avatar gets it stripped to
        // blank (renders as initials downstream), never broadcast as a bot.
        val created = service.create(
            host,
            "Host",
            hostAvatarEmoji = InMemoryRoomService.RESERVED_BOT_AVATAR_EMOJI,
            hostAvatarBackgroundColor = "#112233",
        )
        val room = assertIs<CreateResult.Success>(created).room
        val hostMember = room.members.single()
        assertEquals("", hostMember.avatarEmoji, "the bot avatar is stripped for a human host")
        assertEquals("#112233", hostMember.avatarBackgroundColor, "only the reserved emoji is pruned, not the color")

        // A joiner trying the same is pruned on the join path too.
        val joined = assertIs<JoinResult.Success>(
            service.join(room.code, alice, "Alice", avatarEmoji = InMemoryRoomService.RESERVED_BOT_AVATAR_EMOJI),
        )
        val aliceMember = joined.room.members.single { it.userId == alice }
        assertEquals("", aliceMember.avatarEmoji, "the bot avatar is stripped for a human joiner")
    }

    @Test
    fun create_keepsAnOrdinaryAvatar() = runTest {
        val service = newService()
        val room = assertIs<CreateResult.Success>(
            service.create(host, "Host", hostAvatarEmoji = "🦊"),
        ).room
        assertEquals("🦊", room.members.single().avatarEmoji, "a non-reserved avatar rides through untouched")
    }

    @Test
    fun create_storesBuyIn_andDerivesSettings() = runTest {
        val service = newService()
        val room = when (val r = service.create(host, "Host", maxSeats = 4, buyIn = 20_000)) {
            is CreateResult.Success -> r.room
            else -> error("expected success")
        }
        assertEquals(20_000, room.buyIn)
        assertEquals(20_000, room.settings.startingStack)
        assertEquals(200, room.settings.bigBlind, "blinds derive from buy-in")
    }

    // ---------- bots ----------

    @Test
    fun addBot_seatsRevealedBot_withRobotAvatarAndConnected() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 4)

        val result = service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard)

        val next = assertIs<AddBotResult.Success>(result).room
        assertEquals(2, next.members.size)
        val botMember = next.members.first { it.isBot }
        assertNotNull(botMember.bot, "bot member carries server-side BotSeat truth")
        assertTrue(botMember.bot!!.revealed, "lobby bots are revealed")
        assertEquals(InMemoryRoomService.RESERVED_BOT_AVATAR_EMOJI, botMember.avatarEmoji)
        assertEquals(1, botMember.seatIndex, "bot fills the next free seat")
        assertTrue(botMember.isConnected, "bots are always present so the reaper never frees them")
        assertNull(botMember.disconnectedAt)
    }

    @Test
    fun addBot_rejectsNonHost() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        service.join(room.code, alice, "Alice")

        val result = service.addBot(room.code, requestedBy = alice, difficulty = BotDifficulty.Standard)

        assertIs<AddBotResult.NotHost>(result)
    }

    @Test
    fun addBot_allowsSoleHumanOfMatchmakingRoom() = runTest {
        val service = newService()
        val result = service.findOrJoinPublic(
            userId = host,
            name = "Host",
            minBuyIn = 1_000,
            maxBuyIn = 100_000,
            blockedUserIds = emptySet(),
        )
        val room = assertIs<MatchmakingResult.Created>(result).room
        assertEquals(SYSTEM_HOST_USER_ID, room.hostUserId, "matchmaker rooms carry the synthetic host")
        service.markConnected(room.code, host, connected = true)

        val added = service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard)

        assertIs<AddBotResult.Success>(
            added,
            "the sole human of a matchmaker-created room wields host powers (ROOM-16)",
        )
    }

    @Test
    fun addBot_promotesNextConnectedHuman_whenTaggedHostIsDisconnected() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        service.join(room.code, alice, "Alice")
        service.markConnected(room.code, alice, connected = true)

        val result = service.addBot(room.code, requestedBy = alice, difficulty = BotDifficulty.Standard)

        assertIs<AddBotResult.Success>(
            result,
            "the first connected human wields host powers while the tagged host is offline — " +
                "mirrors the client's effective-host promotion",
        )
    }

    @Test
    fun removeBot_allowsSoleHumanOfMatchmakingRoom() = runTest {
        val service = newService()
        val result = service.findOrJoinPublic(
            userId = host,
            name = "Host",
            minBuyIn = 1_000,
            maxBuyIn = 100_000,
            blockedUserIds = emptySet(),
        )
        val room = assertIs<MatchmakingResult.Created>(result).room
        service.markConnected(room.code, host, connected = true)
        val filled = service.fillBotsUpTo(
            code = room.code,
            requestedBy = SYSTEM_HOST_USER_ID,
            target = 2,
            difficulty = BotDifficulty.Standard,
        )
        val bot = assertIs<AddBotResult.Success>(filled).room.members.first { it.isBot }

        val removed = service.removeBot(room.code, requestedBy = host, botUserId = bot.userId)

        assertIs<RemoveBotResult.Success>(removed)
    }

    @Test
    fun addBot_assignsDistinctPersonalities() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 6)

        repeat(3) {
            assertIs<AddBotResult.Success>(
                service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard),
            )
        }

        val bots = service.find(room.code)!!.members.filter { it.isBot }
        val names = bots.map { it.bot!!.personality.name }
        assertEquals(3, names.size)
        assertEquals(names.size, names.toSet().size, "each bot gets a distinct personality within the roster")
    }

    @Test
    fun addBot_respectsCapacity() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 2)
        assertIs<AddBotResult.Success>(
            service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard),
        )

        val full = service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard)

        assertIs<AddBotResult.Full>(full)
    }

    @Test
    fun addBot_rejectedOncePlaying() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host")
        service.markPlaying(room.code)

        val result = service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard)

        val notJoinable = assertIs<AddBotResult.NotJoinable>(result)
        assertEquals(RoomStatus.Playing, notJoinable.status)
    }

    @Test
    fun addBot_rejectsOccupiedSeat() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 4)

        // Seat 0 is the host.
        val result = service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard, seatIndex = 0)

        assertIs<AddBotResult.SeatTaken>(result)
    }

    @Test
    fun bot_isNotIndividuallyReaped_butABotOnlyRoomIsGCd() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = when (val r = service.create(host, "Host", maxSeats = 4)) {
            is CreateResult.Success -> r.room
            else -> error("expected create success")
        }
        service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard)

        clock.advance(60.minutes)
        val swept = service.sweepDisconnected(maxIdle = 5.minutes)

        // The bot itself is never in the reaped set (it's "connected") — only the
        // never-connected host is. But a room left with nothing but bots has no
        // one watching, so it's GC'd rather than left running hands to nobody.
        assertEquals(1, swept.membersReaped, "just the host was reaped; the bot is immune")
        assertNull(service.find(room.code), "a bot-only room is torn down, not kept alive")
    }

    @Test
    fun removeBot_removesBot_andRejectsNonHostAndHumans() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.join(room.code, alice, "Alice")
        val withBot = assertIs<AddBotResult.Success>(
            service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard),
        ).room
        val botId = withBot.members.first { it.isBot }.userId

        assertIs<RemoveBotResult.NotHost>(service.removeBot(room.code, requestedBy = alice, botUserId = botId))
        assertIs<RemoveBotResult.NotABot>(service.removeBot(room.code, requestedBy = host, botUserId = alice))

        val removed = assertIs<RemoveBotResult.Success>(
            service.removeBot(room.code, requestedBy = host, botUserId = botId),
        ).room
        assertTrue(removed.members.none { it.isBot }, "the bot seat is freed")
        assertEquals(2, removed.members.size, "host + alice remain")
    }

    @Test
    fun sweepDisconnected_delegatesDurableCleanup_excludingLiveCodes() = runTest {
        val clock = AdvanceableClock()
        val store = RecordingRoomStore()
        val service = InMemoryRoomService(clock = clock, random = Random(0L), store = store)
        // One live room (host stays connected → never reaped, stays in memory).
        val live = service.createOrFail(host, "Host")
        service.markConnected(live.code, host, connected = true)
        clock.advance(10.minutes)

        val result = service.sweepDisconnected(maxIdle = 5.minutes)

        assertEquals(1, store.deleteStaleCalls.size, "durable orphan cleanup ran once")
        val (olderThan, keepCodes) = store.deleteStaleCalls.single()
        assertEquals(clock.now() - 5.minutes, olderThan, "cutoff = now - maxIdle")
        assertTrue(live.code in keepCodes, "a still-live room is never offered up for durable deletion")
        assertEquals(2, result.orphanedRoomsReaped, "the store's reported count flows back out")
    }

    // ---------- room-closed listener (session teardown hook) ----------

    @Test
    fun lastHumanLeaving_firesRoomClosedListener_withTheFinalRoom() = runTest {
        val listener = RecordingRoomClosedListener()
        val service = InMemoryRoomService(clock = FixedClock(), random = Random(0L), roomClosedListener = listener)
        val room = service.createOrFail(host, "Host")

        service.leave(room.code, host) // last human out → GC

        assertEquals(listOf(room.code), listener.closedCodes, "teardown fires the listener so the session ends")
    }

    @Test
    fun leavingABotOnlyTable_firesRoomClosedListener() = runTest {
        val listener = RecordingRoomClosedListener()
        val service = InMemoryRoomService(clock = FixedClock(), random = Random(0L), roomClosedListener = listener)
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard)

        service.leave(room.code, host) // only bots remain → GC

        assertEquals(listOf(room.code), listener.closedCodes, "a bot-only table closes its session too")
    }

    @Test
    fun reaper_firesRoomClosedListener_whenItEmptiesTheRoom() = runTest {
        val listener = RecordingRoomClosedListener()
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L), roomClosedListener = listener)
        val room = service.createOrFail(host, "Host")
        val droppedAt = service.find(room.code)!!.memberFor(host)!!.disconnectedAt!!

        service.reapIfStillDisconnected(room.code, host, droppedAt) // last seat reaped → GC

        assertEquals(listOf(room.code), listener.closedCodes)
    }

    @Test
    fun sweep_firesRoomClosedListener_perClosedRoom() = runTest {
        val listener = RecordingRoomClosedListener()
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L), roomClosedListener = listener)
        val a = service.createOrFail(host, "A")
        val b = service.createOrFail(alice, "B")
        clock.advance(10.minutes) // both never connected → both sweepable

        service.sweepDisconnected(maxIdle = 5.minutes)

        assertEquals(setOf(a.code, b.code), listener.closedCodes.toSet(), "every swept-empty room closes its session")
    }

    @Test
    fun normalLeave_doesNotFireListener_roomStillAlive() = runTest {
        val listener = RecordingRoomClosedListener()
        val service = InMemoryRoomService(clock = FixedClock(), random = Random(0L), roomClosedListener = listener)
        val room = service.createOrFail(host, "Host", maxSeats = 4)
        service.join(room.code, alice, "Alice")

        service.leave(room.code, host) // alice still seated → room lives on

        assertTrue(listener.closedCodes.isEmpty(), "the session only ends when the room actually closes")
    }

    // ---------- fillBotsUpTo (atomic disclosed-bot fallback fill) ----------

    @Test
    fun fillBotsUpTo_fillsToTarget_andIsIdempotent() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 6)

        val filled = assertIs<AddBotResult.Success>(
            service.fillBotsUpTo(room.code, host, target = 4, difficulty = BotDifficulty.Standard, revealed = true),
        ).room
        assertEquals(4, filled.members.size, "filled the lone human up to the target of 4")
        assertEquals(3, filled.members.count { it.isBot }, "3 disclosed bots added")
        assertTrue(filled.members.filter { it.isBot }.all { it.bot!!.revealed }, "bots are disclosed (revealed)")

        // A second tap (double-tap / retry) is a no-op — already at target.
        val again = assertIs<AddBotResult.Success>(
            service.fillBotsUpTo(room.code, host, target = 4, difficulty = BotDifficulty.Standard, revealed = true),
        ).room
        assertEquals(4, again.members.size, "idempotent: already at target adds nothing")
    }

    @Test
    fun fillBotsUpTo_concurrentDoubleTap_neverOverfillsPastTarget() = runTest {
        val service = newService()
        val room = service.createOrFail(host, "Host", maxSeats = 6)

        // Two racing taps. Each fill is one atomic critical section, so the second
        // sees the table already at target and adds nothing — never a packed six.
        val results = (0..1).map {
            async { service.fillBotsUpTo(room.code, host, target = 4, difficulty = BotDifficulty.Standard, revealed = true) }
        }.awaitAll()

        results.forEach { assertIs<AddBotResult.Success>(it) }
        assertEquals(4, service.find(room.code)!!.members.size, "concurrent fills converge on the target, not maxSeats")
    }

    // ---------- trimBotForNewHumans ("bots step aside for humans") ----------

    @Test
    fun trimBotForNewHumans_rescuedPair_trimsToACushion_notABareHeadsUp() = runTest {
        val service = newService()
        val code = service.openPublicBotTable() // alice + 3 disclosed bots

        // A second human (bob) is matched into the lonely player's table.
        val joined = assertIs<MatchmakingResult.Joined>(
            service.findOrJoinPublic(bob, "Bob", 1_000, 1_000, emptySet()),
        ).room
        assertEquals(code, joined.code, "bob landed in alice's existing table, not a fresh one")
        assertEquals(2, joined.members.count { !it.isBot }, "two humans now seated")
        assertEquals(3, joined.members.count { it.isBot }, "the 3 bots are still there for now")

        // Hand 1 boundary: exactly one bot steps aside, and we learn which.
        val trimmed1 = service.trimBotForNewHumans(code, handNumber = 1)
        assertNotNull(trimmed1, "a bot was trimmed once two humans are present")
        val afterHand1 = service.find(code)!!
        assertEquals(2, afterHand1.members.count { it.isBot }, "one bot gone")
        assertTrue(afterHand1.members.none { it.userId == trimmed1 }, "the returned id is the bot that left")
        assertEquals(2, afterHand1.members.count { !it.isBot }, "both humans stay")

        // A duplicate call for the SAME hand (another socket subscriber) is a no-op.
        assertNull(service.trimBotForNewHumans(code, handNumber = 1), "idempotent per hand — no second bot dropped")
        assertEquals(2, service.find(code)!!.members.count { it.isBot })

        // Hand 2 sheds one more, down to the cushion: two humans + one bot.
        assertNotNull(service.trimBotForNewHumans(code, handNumber = 2))
        assertEquals(1, service.find(code)!!.members.count { it.isBot }, "trimmed down to a one-bot cushion")

        // Hand 3+: the cushion is HELD — a rescued pair isn't dropped to a bare
        // heads-up. The last bot stays until a third human arrives (or it busts).
        assertNull(service.trimBotForNewHumans(code, handNumber = 3), "holds the cushion bot for the pair")
        assertNull(service.trimBotForNewHumans(code, handNumber = 4), "still held the hand after")
        assertEquals(1, service.find(code)!!.members.count { it.isBot }, "two humans + one cushion bot, not a bare duel")
    }

    @Test
    fun trimBotForNewHumans_thirdHumanArrives_dropsTheCushionBot_allHuman() = runTest {
        val service = newService()
        val code = service.openPublicBotTable() // alice + 3 bots
        service.findOrJoinPublic(bob, "Bob", 1_000, 1_000, emptySet()) // rescued pair

        // Trim down to the held cushion (2 humans + 1 bot).
        service.trimBotForNewHumans(code, handNumber = 1)
        service.trimBotForNewHumans(code, handNumber = 2)
        assertNull(service.trimBotForNewHumans(code, handNumber = 3), "cushion held at the pair")
        assertEquals(1, service.find(code)!!.members.count { it.isBot })

        // A third human shows up — now the table carries itself, so the cushion goes.
        val joined = assertIs<MatchmakingResult.Joined>(
            service.findOrJoinPublic(carol, "Carol", 1_000, 1_000, emptySet()),
        ).room
        assertEquals(3, joined.members.count { !it.isBot }, "three humans now seated")

        assertNotNull(service.trimBotForNewHumans(code, handNumber = 4), "with three humans the cushion drops")
        val finalRoom = service.find(code)!!
        assertEquals(0, finalRoom.members.count { it.isBot }, "the last bot bows out — an all-human table")
        assertEquals(3, finalRoom.members.count { !it.isBot })
    }

    @Test
    fun trimBotForNewHumans_lonePlayerVsBots_keepsEveryBot() = runTest {
        val service = newService()
        val code = service.openPublicBotTable() // alice alone + 3 bots

        // Still just one human — this is the real-money practice table, so the
        // bots stay put. We only shed bots once a real human is rescued in.
        assertNull(service.trimBotForNewHumans(code, handNumber = 1))
        assertEquals(3, service.find(code)!!.members.count { it.isBot }, "a lone human keeps the bots")
    }

    @Test
    fun trimBotForNewHumans_privateTable_neverTrims() = runTest {
        val service = newService()
        // A human-hosted PRIVATE room with a deliberately-added bot + a 2nd human.
        val room = service.createOrFail(host, "Host", maxSeats = 6)
        service.join(room.code, alice, "Alice")
        assertIs<AddBotResult.Success>(service.addBot(room.code, host, BotDifficulty.Standard))

        // Two humans + a bot — but bots in a host's room are a deliberate choice,
        // not matchmaking placeholders, so they're never auto-trimmed.
        assertNull(service.trimBotForNewHumans(room.code, handNumber = 1))
        assertEquals(1, service.find(room.code)!!.members.count { it.isBot }, "private tables never shed bots")
    }

    @Test
    fun trimBotForNewHumans_unknownRoom_isNull() = runTest {
        assertNull(newService().trimBotForNewHumans("ZZZZZZ", handNumber = 1))
    }

    // ---------- findPublicCandidates (chooser discovery, read-only) ----------

    @Test
    fun findPublicCandidates_listsEligibleInRangeTables_withoutSeatingAnyone() = runTest {
        val service = newService()
        // alice opens a public table at the 5k tier (lone searcher, no bots yet).
        val table = assertIs<MatchmakingResult.Created>(
            service.findOrJoinPublic(alice, "Alice", 5_000, 5_000, emptySet()),
        ).room

        val candidates = service.findPublicCandidates(bob, 5_000, 5_000, emptySet())
        assertEquals(listOf(table.code), candidates.map { it.code }, "the in-range table is offered")
        // Read-only: bob was not seated anywhere by the call.
        assertEquals(1, service.find(table.code)!!.members.size, "the table still holds only alice")
    }

    @Test
    fun findPublicCandidates_excludesOutOfRangeTables() = runTest {
        val service = newService()
        service.findOrJoinPublic(alice, "Alice", 1_000, 1_000, emptySet())
        val big = assertIs<MatchmakingResult.Created>(
            service.findOrJoinPublic(bob, "Bob", 100_000, 100_000, emptySet()),
        ).room

        val candidates = service.findPublicCandidates(carol, 100_000, 100_000, emptySet())
        assertEquals(listOf(big.code), candidates.map { it.code }, "only the matching-tier table qualifies")
    }

    @Test
    fun findPublicCandidates_skipsTablesWithABlockedMember() = runTest {
        val service = newService()
        val enemyRoom = assertIs<MatchmakingResult.Created>(
            service.findOrJoinPublic(alice, "Alice", 5_000, 5_000, emptySet()),
        ).room

        val candidates = service.findPublicCandidates(bob, 5_000, 5_000, blockedUserIds = setOf(alice))
        assertTrue(candidates.none { it.code == enemyRoom.code }, "a table with a blocked member is hidden")
    }

    @Test
    fun findPublicCandidates_skipsPrivateAndFullTables() = runTest {
        val service = newService()
        // A code-only private room — never matchmaking inventory.
        val private = service.createOrFail(host, "Host", maxSeats = 6)
        // A full public table — no seat to offer.
        val full = assertIs<MatchmakingResult.Created>(
            service.findOrJoinPublic(alice, "Alice", 5_000, 5_000, emptySet()),
        ).room
        assertIs<AddBotResult.Success>(
            service.fillBotsUpTo(full.code, SYSTEM_HOST_USER_ID, target = 6, difficulty = BotDifficulty.Standard, revealed = true),
        )

        val candidates = service.findPublicCandidates(bob, 5_000, 5_000, emptySet())
        assertTrue(candidates.none { it.code == private.code }, "private rooms are not matchmaking candidates")
        assertTrue(candidates.none { it.code == full.code }, "a full table is not offered")
    }

    @Test
    fun findPublicCandidates_ordersMostHumansFirst() = runTest {
        val service = newService()
        // Two human-hosted Open tables at the same tier, built directly so they
        // stay distinct (findOrJoinPublic would otherwise collapse them into one).
        val onePlayer = assertIs<CreateResult.Success>(
            service.create(alice, "Alice", buyIn = 5_000, visibility = RoomVisibility.Open),
        ).room
        val twoPlayers = assertIs<CreateResult.Success>(
            service.create(bob, "Bob", buyIn = 5_000, visibility = RoomVisibility.Open),
        ).room
        service.join(twoPlayers.code, carol, "Carol")

        val candidates = service.findPublicCandidates(host, 5_000, 5_000, emptySet())
        assertEquals(
            listOf(twoPlayers.code, onePlayer.code),
            candidates.map { it.code },
            "the table with more real humans is offered first",
        )
    }

    @Test
    fun findPublicCandidates_noMatch_isEmpty() = runTest {
        val service = newService()
        assertTrue(service.findPublicCandidates(alice, 5_000, 5_000, emptySet()).isEmpty())
    }

    /**
     * A public matchmaker table seated with one human (alice) + 3 disclosed bots —
     * the lonely-player "play with bots" fallback. Returns the room code.
     */
    private suspend fun InMemoryRoomService.openPublicBotTable(): String {
        val created = assertIs<MatchmakingResult.Created>(
            findOrJoinPublic(alice, "Alice", 1_000, 1_000, emptySet()),
        ).room
        assertIs<AddBotResult.Success>(
            fillBotsUpTo(created.code, SYSTEM_HOST_USER_ID, target = 4, difficulty = BotDifficulty.Standard, revealed = true),
        )
        return created.code
    }

    // ---------- scaffolding ----------

    private fun newService(seed: Long = 0L): InMemoryRoomService = InMemoryRoomService(
        clock = FixedClock(),
        random = Random(seed),
    )

    /** A durable store that actually round-trips, so a test can stand a second
     *  service on the same rows and model a process restart. */
    private class FakeRoomStore : com.dangerfield.cards.server.domain.RoomStore {
        private val rows = mutableMapOf<String, com.dangerfield.cards.server.domain.Room>()
        override suspend fun save(room: com.dangerfield.cards.server.domain.Room) {
            rows[room.code] = room
        }
        override suspend fun delete(code: String) {
            rows.remove(code)
        }
        override suspend fun load(code: String): com.dangerfield.cards.server.domain.Room? = rows[code]
        override suspend fun deleteStaleRooms(olderThan: Instant, keepCodes: Set<String>): Int = 0
    }

    /** Records which room codes were torn down, for the teardown-hook tests. */
    private class RecordingRoomClosedListener : com.dangerfield.cards.server.domain.RoomClosedListener {
        val closedCodes = mutableListOf<String>()
        override suspend fun onRoomClosed(room: com.dangerfield.cards.server.domain.Room) {
            closedCodes += room.code
        }
    }

    /** Records [deleteStaleRooms] calls so a sweep test can assert the cutoff +
     *  keep-set without a real database; returns a fixed orphan count. */
    private class RecordingRoomStore : com.dangerfield.cards.server.domain.RoomStore {
        val deleteStaleCalls = mutableListOf<Pair<Instant, Set<String>>>()
        override suspend fun save(room: com.dangerfield.cards.server.domain.Room) = Unit
        override suspend fun delete(code: String) = Unit
        override suspend fun load(code: String): com.dangerfield.cards.server.domain.Room? = null
        override suspend fun deleteStaleRooms(olderThan: Instant, keepCodes: Set<String>): Int {
            deleteStaleCalls += olderThan to keepCodes
            return 2
        }
    }

    private class FixedClock(private val ms: Long = 1_700_000_000_000) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(ms)
    }

    /**
     * Mutable wall-clock for sweep tests — production code only reads
     * `now()`, so a single setter on the test side is enough to time-
     * travel without coordinating coroutine dispatchers.
     */
    private class AdvanceableClock(startMs: Long = 1_700_000_000_000) : Clock {
        private var current: Instant = Instant.fromEpochMilliseconds(startMs)
        override fun now(): Instant = current
        fun advance(by: kotlin.time.Duration) {
            current = current + by
        }
    }

}

package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.server.domain.AddBotResult
import com.dangerfield.cards.server.domain.CreateResult
import com.dangerfield.cards.server.domain.JoinResult
import com.dangerfield.cards.server.domain.LeaveResult
import com.dangerfield.cards.server.domain.RemoveBotResult
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
    fun bot_isImmuneToSweep() = runTest {
        val clock = AdvanceableClock()
        val service = InMemoryRoomService(clock = clock, random = Random(0L))
        val room = when (val r = service.create(host, "Host", maxSeats = 4)) {
            is CreateResult.Success -> r.room
            else -> error("expected create success")
        }
        service.addBot(room.code, requestedBy = host, difficulty = BotDifficulty.Standard)

        clock.advance(60.minutes)
        val swept = service.sweepDisconnected(maxIdle = 5.minutes)

        // The never-connected host is reaped; the bot is not (it's "connected").
        val survivors = service.find(room.code)?.members ?: emptyList()
        assertEquals(1, survivors.size)
        assertTrue(survivors.single().isBot, "only the bot survives the sweep")
        assertEquals(1, swept.membersReaped, "just the host was swept")
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

    // ---------- scaffolding ----------

    private fun newService(seed: Long = 0L): InMemoryRoomService = InMemoryRoomService(
        clock = FixedClock(),
        random = Random(seed),
    )

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

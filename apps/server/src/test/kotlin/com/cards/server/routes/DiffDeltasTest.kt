package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomMember
import com.dangerfield.cards.server.domain.RoomStatus
import com.dangerfield.cards.server.domain.UserId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins [diffDeltas] — the function the room WebSocket publisher uses to
 * derive `MemberJoined` / `MemberLeft` / `MemberPresenceChanged` events
 * between consecutive [Room] snapshots. Each event a client receives for
 * UI toasts ("Alice joined", "Bob disconnected") flows through this
 * function; the authoritative [RoomSocketEventDto.Snapshot] goes out
 * alongside.
 *
 * A regression here silently swaps "Alice joined" for "Alice left" — or
 * worse, fires neither — which manifests in the client as a wrong toast
 * + the right snapshot. The Snapshot keeps the room state correct, so
 * the bug doesn't break gameplay, but it does break the social
 * affordance that lets a user tell the difference between "the table
 * lost a player" and "the table gained one."
 */
@OptIn(ExperimentalTime::class)
class DiffDeltasTest {

    @Test
    fun emitsMemberJoined_whenNewMemberAppears() {
        val previous = room(alice(seat = 0, connected = true))
        val next = room(
            alice(seat = 0, connected = true),
            bob(seat = 1, connected = true),
        )

        val deltas = diffDeltas(previous, next)

        val joined = deltas.filterIsInstance<RoomSocketEventDto.MemberJoined>()
        assertEquals(1, joined.size)
        assertEquals(BOB_ID.value.toString(), joined.single().member.userId)
        assertEquals(1, joined.single().member.seatIndex)
        assertEquals(true, joined.single().member.isConnected)
    }

    @Test
    fun emitsMemberLeft_whenMemberDisappears() {
        val previous = room(
            alice(seat = 0, connected = true),
            bob(seat = 1, connected = true),
        )
        val next = room(alice(seat = 0, connected = true))

        val deltas = diffDeltas(previous, next)

        val left = deltas.filterIsInstance<RoomSocketEventDto.MemberLeft>()
        assertEquals(1, left.size)
        assertEquals(BOB_ID.value.toString(), left.single().userId)
    }

    @Test
    fun emitsMemberPresenceChanged_whenIsConnectedFlips() {
        val previous = room(alice(seat = 0, connected = true))
        val next = room(alice(seat = 0, connected = false))

        val deltas = diffDeltas(previous, next)

        val presence = deltas.filterIsInstance<RoomSocketEventDto.MemberPresenceChanged>()
        assertEquals(1, presence.size)
        assertEquals(ALICE_ID.value.toString(), presence.single().userId)
        assertEquals(false, presence.single().isConnected)
    }

    @Test
    fun emitsMemberPresenceChanged_inBothDirections() {
        // Reconnect path: false → true. The publisher cares about both
        // edges, not just the disconnect.
        val previous = room(alice(seat = 0, connected = false))
        val next = room(alice(seat = 0, connected = true))

        val deltas = diffDeltas(previous, next)

        val presence = deltas.filterIsInstance<RoomSocketEventDto.MemberPresenceChanged>()
        assertEquals(1, presence.size)
        assertEquals(true, presence.single().isConnected)
    }

    @Test
    fun returnsEmpty_whenNothingChanged() {
        // Idempotent snapshots are common — the same Room can arrive
        // twice (e.g. a no-op mutation) and the publisher must emit no
        // delta. `distinctUntilChanged` on the upstream flow handles
        // the trivially-identical case, but diffDeltas backs it up.
        val room = room(
            alice(seat = 0, connected = true),
            bob(seat = 1, connected = false),
        )

        assertEquals(emptyList(), diffDeltas(room, room))
    }

    @Test
    fun returnsEmpty_whenOnlyNonPresenceFieldsChanged() {
        // Renames / seat-shuffles aren't reported as deltas per the
        // file docstring — they're implicitly captured by the Snapshot
        // that goes out alongside. Pin this so a future "let's emit a
        // rename event" lands deliberately rather than as a side effect
        // of a member-equality change.
        val previous = room(alice(seat = 0, connected = true, name = "Alice"))
        val next = room(alice(seat = 0, connected = true, name = "Alice (renamed)"))

        assertEquals(emptyList(), diffDeltas(previous, next))
    }

    @Test
    fun orderingFollowsDocumentedContract_presenceLeavesJoins() {
        // Docstring contract: "presence changes first (cheap, frequent),
        // then leaves (drop them before render), then joins (add at the
        // end)". This ordering lets a client renderer batch the deltas
        // in the same paint without flickering a "joined then left"
        // intermediate state. Bob disconnects, charlie leaves, dave
        // joins — assert the right ordering.
        val previous = room(
            alice(seat = 0, connected = true),
            bob(seat = 1, connected = true),
            charlie(seat = 2, connected = true),
        )
        val next = room(
            alice(seat = 0, connected = true),
            bob(seat = 1, connected = false),
            dave(seat = 2, connected = true),
        )

        val deltas = diffDeltas(previous, next)

        // Expect: PresenceChanged(bob, false), MemberLeft(charlie), MemberJoined(dave)
        assertEquals(3, deltas.size)
        assertTrue(deltas[0] is RoomSocketEventDto.MemberPresenceChanged, "first delta must be presence")
        assertTrue(deltas[1] is RoomSocketEventDto.MemberLeft, "second delta must be leave")
        assertTrue(deltas[2] is RoomSocketEventDto.MemberJoined, "third delta must be join")
        assertEquals(BOB_ID.value.toString(), (deltas[0] as RoomSocketEventDto.MemberPresenceChanged).userId)
        assertEquals(CHARLIE_ID.value.toString(), (deltas[1] as RoomSocketEventDto.MemberLeft).userId)
        assertEquals(DAVE_ID.value.toString(), (deltas[2] as RoomSocketEventDto.MemberJoined).member.userId)
    }

    @Test
    fun freshRoom_emitsAllJoins() {
        // No previous member set → every member in next surfaces as a
        // join. Documented seed case for the publisher's initial scan
        // (the first `previous` is the empty room, then the upstream
        // flow emits the real first snapshot).
        val previous = room(/* empty */)
        val next = room(
            alice(seat = 0, connected = true),
            bob(seat = 1, connected = true),
        )

        val deltas = diffDeltas(previous, next)

        val joined = deltas.filterIsInstance<RoomSocketEventDto.MemberJoined>()
        assertEquals(2, joined.size)
        // No leaves or presence events from a fresh-room seed.
        assertTrue(deltas.none { it is RoomSocketEventDto.MemberLeft })
        assertTrue(deltas.none { it is RoomSocketEventDto.MemberPresenceChanged })
    }

    @Test
    fun teardown_emitsAllLeaves() {
        // Symmetric end-of-room case — last sweep before RoomClosed.
        val previous = room(
            alice(seat = 0, connected = true),
            bob(seat = 1, connected = true),
        )
        val next = room(/* empty */)

        val deltas = diffDeltas(previous, next)

        val left = deltas.filterIsInstance<RoomSocketEventDto.MemberLeft>()
        assertEquals(2, left.size)
        assertTrue(deltas.none { it is RoomSocketEventDto.MemberJoined })
        assertTrue(deltas.none { it is RoomSocketEventDto.MemberPresenceChanged })
    }

    // ---------- helpers ----------

    private val ALICE_ID = UserId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"))
    private val BOB_ID = UserId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))
    private val CHARLIE_ID = UserId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"))
    private val DAVE_ID = UserId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"))

    private fun alice(seat: Int, connected: Boolean, name: String = "Alice"): RoomMember =
        member(ALICE_ID, name, seat, connected)

    private fun bob(seat: Int, connected: Boolean, name: String = "Bob"): RoomMember =
        member(BOB_ID, name, seat, connected)

    private fun charlie(seat: Int, connected: Boolean, name: String = "Charlie"): RoomMember =
        member(CHARLIE_ID, name, seat, connected)

    private fun dave(seat: Int, connected: Boolean, name: String = "Dave"): RoomMember =
        member(DAVE_ID, name, seat, connected)

    private fun member(id: UserId, displayName: String, seatIndex: Int, isConnected: Boolean): RoomMember =
        RoomMember(
            userId = id,
            displayName = displayName,
            seatIndex = seatIndex,
            joinedAt = Instant.fromEpochMilliseconds(0L),
            isConnected = isConnected,
        )

    private fun room(vararg members: RoomMember): Room = Room(
        code = "ABCDEF",
        hostUserId = members.firstOrNull()?.userId ?: ALICE_ID,
        createdAt = Instant.fromEpochMilliseconds(0L),
        maxSeats = 6,
        status = RoomStatus.Lobby,
        members = members.toList(),
    )
}

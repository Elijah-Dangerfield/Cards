package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.awaitUntil
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.JoinRoomOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * **The disconnect-grace reaper actually fires.** Existing tests block/unblock
 * the reaper to observe presence flips but never let the grace timer elapse —
 * the seat-freeing path itself was untested over the wire. These inject a
 * sub-second grace ([IntegrationTest.integration]'s `reaperGrace`) so the
 * server's scheduled `reapIfStillDisconnected` runs inside the test, then assert
 * the freed seat / migrated host / GC'd room that the timer produces.
 */
class ReaperGraceExpiryPlayTest : IntegrationTest() {

    @Test
    fun disconnectedMember_pastGrace_isReapedAndSeatFreed() = integration(reaperGrace = 400.milliseconds) {
        val table = seatTwoAndConnect()

        // The non-host joiner drops and stays down past the grace window.
        table.joiner.faults!!.dropAndBlock()

        // The host observes the reaper free the seat — membership drops to one,
        // and the survivor is the host (the joiner's seat is gone, not the host's).
        val swept = table.hostGame.awaitRoom(timeoutMs = 10_000) { it.members.size == 1 }
        assertEquals(
            table.host.userId,
            swept.members.single().userId,
            "the reaper freed the dropped joiner's seat, leaving the host",
        )
    }

    @Test
    fun host_disconnectsPastGrace_seatReaped_andHostMigrates() = integration(reaperGrace = 400.milliseconds) {
        val table = seatTwoAndConnect()

        // The HOST drops past grace — the reaper must free the host's seat AND
        // the room survives with the joiner promoted (no stale host pointer).
        table.host.faults!!.dropAndBlock()

        val swept = table.joinerGame.awaitRoom(timeoutMs = 10_000) { it.members.size == 1 }
        val survivor = swept.members.single()
        assertEquals(table.joiner.userId, survivor.userId, "the joiner survives the host's reap")
        assertEquals(
            table.joiner.userId,
            swept.hostUserId,
            "host migrated to the survivor — no ghost host pointing at the reaped member",
        )
    }

    @Test
    fun twoMembersDisconnect_pastGrace_bothReapedIndependently() = integration(reaperGrace = 400.milliseconds) {
        val host = client(faulty = true)
        val a = client(faulty = true)
        val b = client(faulty = true)
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom(maxSeats = 3))
        val code = created.room.code
        assertIs<JoinRoomOutcome.Success>(a.repository.joinRoom(code))
        assertIs<JoinRoomOutcome.Success>(b.repository.joinRoom(code))
        val hostGame = gameplay(host.connect(code)).also { it.awaitConnected() }
        gameplay(a.connect(code)).awaitConnected()
        gameplay(b.connect(code)).awaitConnected()
        hostGame.awaitRoom { it.members.size == 3 }

        // Both non-host members drop at once — each has its own grace timer, so
        // both seats free independently, not via one global sweep.
        a.faults!!.dropAndBlock()
        b.faults!!.dropAndBlock()

        val swept = hostGame.awaitRoom(timeoutMs = 10_000) { it.members.size == 1 }
        assertEquals(host.userId, swept.members.single().userId, "both dropped seats reaped; host remains")
    }

    @Test
    fun reapedMember_canRejoin_andIsSeatedAgain() = integration(reaperGrace = 400.milliseconds) {
        val table = seatTwoAndConnect()

        table.joiner.faults!!.dropAndBlock()
        table.hostGame.awaitRoom(timeoutMs = 10_000) { it.members.size == 1 }

        // The reaped player rejoins via HTTP — a stale disconnectedAt/old seat
        // must not block re-entry. (Membership is what matters; the joiner's old
        // socket may stay blocked, but the HTTP join re-adds them.)
        table.joiner.faults!!.blockReconnects = false
        assertIs<JoinRoomOutcome.Success>(table.joiner.repository.joinRoom(table.code))

        val rejoined = table.hostGame.awaitRoom { state ->
            state.members.size == 2 && state.members.any { it.userId == table.joiner.userId }
        }
        assertTrue(
            rejoined.members.any { it.userId == table.joiner.userId },
            "a reaped member can rejoin and is seated again",
        )
    }

    @Test
    fun soleMember_pastGrace_roomIsReapedAndClosed() = integration(reaperGrace = 400.milliseconds) {
        val host = client(faulty = true)
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom())
        val code = created.room.code
        gameplay(host.connect(code)).awaitConnected()

        // The only member drops past grace — the reaper GCs the empty room.
        host.faults!!.dropAndBlock()

        // Read-only probe (a join would re-create membership and keep it alive).
        awaitUntil(timeoutMs = 10_000) { !server.roomExists(code) }
    }
}

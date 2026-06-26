package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.awaitUntil
import com.cards.integration.helpers.cards
import com.cards.integration.helpers.seatTwoAndConnect
import com.cards.integration.helpers.stackedDeck
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
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
    fun allInPlayer_reapedMidHand_isSettledAtShowdown_chipsConserve() =
        integration(reaperGrace = 400.milliseconds) {
            // The other reaper tests free a seat / migrate the host, but never reap a
            // player who is committed all-in in a live hand. That seat can't simply be
            // folded out (an all-in player keeps their showdown right), so the reaper's
            // MemberLeft must route through the SAME deferred-settlement path an explicit
            // leave uses: defer while the hand is live, pay the resolved stack when it
            // completes. Cashing them out at 0 on reap would burn the pot they win.
            val table = seatTwoAndConnect()
            // The first-to-act seat (the shover) holds the winning hand, so the deferred
            // settlement owes them the whole pot — the case where a wrong cash-out hurts.
            server.scriptDeck(
                table.code,
                stackedDeck(
                    holeBySeat = listOf(cards("As Ad"), cards("2c 7d")),
                    board = cards("Ah 9c 4s Kd 6h"),
                ),
            )

            table.hostGame.startHand()
            val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
            val shover = dealt.actingSeatIndex!!
            val shoverClient = table.actingClient(dealt)
            val stayer = table.other(shoverClient)
            val stayerGame = table.gameOf(stayer)
            val stayerSeat = dealt.seats.first { it.playerId == stayer.userId }.index
            table.gameForSeat(dealt, shover).submit(PlayerIntent.AllIn(seatIndex = shover))

            // The all-in player drops and stays down past the grace window. The reaper
            // fires MemberLeft for them; because they're all-in in a live hand the
            // settlement is deferred, not cashed out at their live stack of 0.
            shoverClient.faults!!.dropAndBlock()
            stayerGame.awaitRoom(timeoutMs = 10_000) { it.members.size == 1 }

            // The opponent (still connected) calls the reaped player's shove; the board
            // runs out and the departed all-in winner is settled at their resolved stack.
            stayerGame.nextSnapshot { it.actingSeatIndex == stayerSeat }
            val callAck = stayerGame.submit(PlayerIntent.Call(seatIndex = stayerSeat))
            check(callAck.accepted) { "stayer's call rejected: ${callAck.error}" }
            stayerGame.nextSnapshot { it.street == BettingRound.Complete }

            // Chips conserve end-to-end: the reaped player is paid the pot they won
            // (settled when the showdown completed), the busted opponent leaves and
            // cashes out their real 0. Burning the reaped winner's pot would settle the
            // table below 20,000.
            stayer.repository.leaveRoom(table.code)
            awaitUntil(timeoutMs = 10_000) {
                val h = server.walletBalance(table.host.userId)
                val j = server.walletBalance(table.joiner.userId)
                h != null && j != null && h + j == 20_000L
            }
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

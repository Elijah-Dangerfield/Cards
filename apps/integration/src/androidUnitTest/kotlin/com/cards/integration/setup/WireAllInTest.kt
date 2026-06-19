package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * All-in settlement over the REAL wire. The passive suite never shoves, so the
 * all-in intent + its pot settlement had no end-to-end coverage. Heads-up with
 * equal stacks the result is winner-take-all (or a chop), exercising the shove,
 * the call-the-shove path, the run-out to showdown, and the final award — all
 * through the socket against the real engine.
 */
class WireAllInTest : IntegrationTest() {

    @Test
    fun shoveAndCall_runsToShowdown_andSettlesWinnerTakeAll() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val shover = dealt.actingSeatIndex!!
        val shoveAck = table.gameForSeat(dealt, shover).submit(PlayerIntent.AllIn(seatIndex = shover))
        assertTrue(shoveAck.accepted, "an all-in must be accepted over the wire, error=${shoveAck.error}")

        // The opponent now faces the shove and calls it.
        val facing = table.hostGame.nextSnapshot {
            it.actingSeatIndex != null && it.actingSeatIndex != shover
        }
        val caller = facing.actingSeatIndex!!
        val callAck = table.gameForSeat(facing, caller).submit(PlayerIntent.Call(seatIndex = caller))
        assertTrue(callAck.accepted, "calling the shove must be accepted, error=${callAck.error}")

        val end = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        table.joinerGame.nextSnapshot { it.street == BettingRound.Complete }

        assertEquals(5, end.community.size, "an all-in heads-up runs the board out to showdown")
        val starting = end.settings.startingStack
        assertEquals(
            starting * end.seats.size,
            end.seats.sumOf { it.stack },
            "all chips conserve through the all-in settlement",
        )
        // Equal stacks → one seat takes everything, or it's a chop. No partial
        // values are possible for a single (no side) pot.
        val allowed = setOf(0L, starting, starting * 2)
        assertTrue(
            end.seats.all { it.stack in allowed },
            "heads-up all-in settles winner-take-all or chop, got ${end.seats.map { it.stack }}",
        )
    }
}

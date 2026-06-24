package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.playPassivelyToCompletion
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **Boundary hands.** A heads-up all-in preflop runs the board out to showdown
 * with no further betting, and the dealer button is a real seat that rotates
 * between hands.
 */
class EdgeCasesPlayTest : IntegrationTest() {

    @Test
    fun headsUpAllInPreflop_runsTheBoardOutToShowdown() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        // First to act shoves; the other calls the all-in. Equal stacks → both
        // all-in, so there's no more betting and the engine deals out the board.
        val shoveSeat = dealt.actingSeatIndex!!
        val shove = table.gameForSeat(dealt, shoveSeat).submit(PlayerIntent.AllIn(shoveSeat))
        assertTrue(shove.accepted, "a preflop all-in is legal, error=${shove.error}")

        val facing = table.hostGame.nextSnapshot { it.actingSeatIndex != null && it.actingSeatIndex != shoveSeat }
        val callSeat = facing.actingSeatIndex!!
        val call = table.gameForSeat(facing, callSeat).submit(PlayerIntent.Call(callSeat))
        assertTrue(call.accepted, "calling the all-in is legal, error=${call.error}")

        val complete = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        assertEquals(
            5,
            complete.community.size,
            "both all-in preflop → the full board runs out to the river at showdown",
        )
    }

    @Test
    fun dealerButton_isARealSeat_andRotatesNextHand() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        // Read the button off the completed hand-1 snapshot (it's stable within a
        // hand) — reading + driving from one cursor avoids stranding the forward
        // reader on an already-consumed actionable snapshot.
        val hand1 = table.playPassivelyToCompletion()
        val button1 = hand1.buttonSeatIndex
        assertTrue(
            button1 in hand1.seats.map { it.index },
            "the button points at a real seat ($button1 in ${hand1.seats.map { it.index }})",
        )

        table.hostGame.requestNextHand()
        val hand2 = table.hostGame.nextSnapshot { it.handNumber == 2 && it.actingSeatIndex != null }

        assertNotEquals(button1, hand2.buttonSeatIndex, "the button rotates to the other seat next hand")
    }
}

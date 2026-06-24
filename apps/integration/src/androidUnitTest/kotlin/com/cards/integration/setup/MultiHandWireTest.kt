package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.playPassivelyToCompletion
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Hand-to-hand continuity over the REAL wire. `afterHandCompletes_requestNextHand`
 * proves a fresh hand deals, but not that the button rotates or that stacks carry
 * forward — both are load-bearing for a multi-hand session and neither was pinned.
 */
class MultiHandWireTest : IntegrationTest() {

    /**
     * Friend-game (host-driven) full-hands coverage. Most existing multi-hand
     * coverage ends each hand with a preflop fold; this test plays three
     * consecutive hands deal → preflop → flop → turn → river → showdown → next
     * hand, with the host tapping `requestNextHand` between them. That whole
     * loop is exactly the path CARDS-16 (hand-end stall) lived in, and the
     * owner's review explicitly asked the harness to cover full hands rather
     * than thin slices.
     */
    @Test
    fun threeConsecutiveFullHands_dealThroughShowdown_chipsConserved() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val end1 = table.playPassivelyToCompletion()
        val totalChips = end1.settings.startingStack * end1.seats.size
        var prevButton = end1.buttonSeatIndex
        assertHandSettled(handIdx = 0, end = end1, totalChips = totalChips)

        repeat(2) { i ->
            val handIdx = i + 1
            table.hostGame.requestNextHand()
            val end = table.playPassivelyToCompletion()
            table.joinerGame.nextSnapshot {
                it.handNumber == handIdx + 1 && it.street == BettingRound.Complete
            }
            assertNotEquals(
                prevButton,
                end.buttonSeatIndex,
                "the button must rotate to the other seat between hands (entering hand ${handIdx + 1})",
            )
            prevButton = end.buttonSeatIndex
            assertHandSettled(handIdx = handIdx, end = end, totalChips = totalChips)
        }
    }

    private fun assertHandSettled(
        handIdx: Int,
        end: GameState,
        totalChips: Long,
    ) {
        assertEquals(BettingRound.Complete, end.street, "hand ${handIdx + 1} must reach showdown")
        assertEquals(
            5,
            end.community.size,
            "a called-down hand reaches a five-card showdown (hand ${handIdx + 1})",
        )
        assertEquals(
            totalChips,
            end.seats.sumOf { it.stack },
            "chips conserve across hand ${handIdx + 1} — none created or destroyed",
        )
    }

    @Test
    fun acrossHands_buttonRotates_andStacksCarryOver() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val hand1 = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val button1 = hand1.buttonSeatIndex

        // End hand 1 quickly with a fold so the blinds move some chips.
        val folder = hand1.actingSeatIndex!!
        table.gameForSeat(hand1, folder).submit(PlayerIntent.Fold(seatIndex = folder))
        val end1 = table.hostGame.nextSnapshot {
            it.handNumber == hand1.handNumber && it.street == BettingRound.Complete
        }
        val stacksAfterHand1 = end1.seats.associate { it.index to it.stack }
        assertNotEquals(
            end1.settings.startingStack,
            stacksAfterHand1.getValue(folder),
            "the folder forfeits its blind, so stacks must have moved off the starting value",
        )

        table.hostGame.requestNextHand()
        val hand2 = table.hostGame.nextSnapshot {
            it.handNumber > hand1.handNumber && it.actingSeatIndex != null
        }
        table.joinerGame.nextSnapshot { it.handNumber > hand1.handNumber }

        assertNotEquals(
            button1,
            hand2.buttonSeatIndex,
            "the button must rotate to the other seat on the next hand",
        )
        // Carry-over: the only chips a seat has parted with at the start of
        // hand 2 are its posted blind (contributedThisHand). Adding it back
        // recovers the stack carried in from hand 1's end — proving stacks are
        // NOT reset to the starting value each hand.
        hand2.seats.forEach { seat ->
            assertEquals(
                stacksAfterHand1.getValue(seat.index),
                seat.stack + seat.contributedThisHand,
                "seat ${seat.index}'s stack must carry over from the prior hand",
            )
        }
        assertTrue(
            stacksAfterHand1.values.any { it != end1.settings.startingStack },
            "sanity: the carried stacks differ from the starting stack",
        )
    }
}

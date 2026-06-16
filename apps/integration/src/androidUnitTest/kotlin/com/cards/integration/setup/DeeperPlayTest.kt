package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.Table
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Beyond fold-to-end: real multi-street betting and hand-to-hand advance, driven
 * through the wire. Proves the engine + socket handle actual call/check action
 * and that a new hand can be requested after one completes.
 */
class DeeperPlayTest : IntegrationTest() {

    @Test
    fun headsUp_passiveBetting_advancesPreflopToFlop_dealsThreeCommunityCards() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val flop = table.advancePassivelyTo(BettingRound.Flop)

        assertEquals(BettingRound.Flop, flop.street)
        assertEquals(3, flop.community.size, "the flop should deal three community cards")
    }

    @Test
    fun afterHandCompletes_requestNextHand_dealsANewHand() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        val actingSeat = dealt.actingSeatIndex!!
        table.gameForSeat(dealt, actingSeat).submit(PlayerIntent.Fold(seatIndex = actingSeat))
        val completed = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }

        table.hostGame.requestNextHand()
        val next = table.hostGame.nextSnapshot {
            it.handNumber > completed.handNumber && it.actingSeatIndex != null
        }
        table.joinerGame.nextSnapshot { it.handNumber > completed.handNumber }

        assertTrue(next.handNumber > completed.handNumber, "a fresh hand should be dealt")
    }

    /**
     * Walk the hand forward with the most passive legal action (check if nothing
     * to call, else call) until [target] is reached. Reads from the host's view
     * (bet sizes are public) and routes each action to the seat that owns it,
     * skipping snapshots where the same seat is still to act (already handled).
     */
    private suspend fun Table.advancePassivelyTo(target: BettingRound): GameState {
        var lastActedSeat = -1
        while (true) {
            val state = hostGame.nextSnapshot {
                it.street == target || (it.actingSeatIndex != null && it.actingSeatIndex != lastActedSeat)
            }
            if (state.street == target) return state

            val seatIndex = state.actingSeatIndex!!
            val seat = state.seatAt(seatIndex)
            val toCall = state.currentBetThisStreet - seat.contributedThisStreet
            val ack = gameForSeat(state, seatIndex).submit(
                if (toCall > 0) PlayerIntent.Call(seatIndex) else PlayerIntent.Check(seatIndex),
            )
            check(ack.accepted) { "passive action at seat $seatIndex rejected: ${ack.error}" }
            lastActedSeat = seatIndex
        }
    }
}

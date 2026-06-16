package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.seatFor
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * In-hand play through the REAL wire: two real clients, the real server gameplay
 * engine, real sockets. The heart of the mission — once players are seated, a hand
 * plays, the contract holds, and one client can't act for another.
 */
class InHandPlayTest : IntegrationTest() {

    @Test
    fun twoClients_playHeadsUpHand_toCompletion_viaFold() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        // Both clients see the hand dealt with someone to act.
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // The player to act folds; heads-up that ends the hand.
        val actingSeat = dealt.actingSeatIndex!!
        val ack = table.gameForSeat(dealt, actingSeat).submit(PlayerIntent.Fold(seatIndex = actingSeat))
        assertTrue(ack.accepted, "fold should be accepted, error=${ack.error}")

        table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        table.joinerGame.nextSnapshot { it.street == BettingRound.Complete }
    }

    @Test
    fun holeCards_areScrubbedPerRecipient() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val hostView = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val joinerView = table.joinerGame.nextSnapshot { it.actingSeatIndex != null }

        // Each client sees its own hole cards but never the opponent's.
        assertTrue(hostView.seatFor(table.host).holeCards.isNotEmpty(), "host should see its own cards")
        assertTrue(hostView.seatFor(table.joiner).holeCards.isEmpty(), "host must NOT see joiner's cards")
        assertTrue(joinerView.seatFor(table.joiner).holeCards.isNotEmpty(), "joiner should see its own cards")
        assertTrue(joinerView.seatFor(table.host).holeCards.isEmpty(), "joiner must NOT see host's cards")
    }

    @Test
    fun outOfTurnIntent_isRejected() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }

        // The player who is NOT to act tries to fold their own seat — out of turn.
        val idle = table.other(table.actingClient(dealt))
        val ack = table.gameOf(idle).submit(PlayerIntent.Fold(seatIndex = dealt.seatFor(idle).index))
        assertFalse(ack.accepted, "an out-of-turn intent must be rejected")
    }
}

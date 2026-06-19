package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.advancePassivelyUntil
import com.cards.integration.helpers.playPassivelyToCompletion
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Aggressive betting lines over the REAL wire. The existing in-hand suite only
 * exercises passive call/check and a preflop fold; every recent MP bug lived at
 * the wire, so the seams that carry bets, raises, and late-street folds need
 * their own coverage. Two real clients, the real server engine, real sockets.
 */
class WireBettingLinesTest : IntegrationTest() {

    @Test
    fun raise_ridesTheWire_isApplied_andPlaysToShowdown() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        val dealt = table.hostGame.nextSnapshot { it.actingSeatIndex != null }
        val firstSeat = dealt.actingSeatIndex!!
        // A full raise to two big blinds over the current bet — comfortably
        // within stack and a legal full raise heads-up.
        val raiseTo = dealt.currentBetThisStreet + dealt.settings.bigBlind * 2
        val ack = table.gameForSeat(dealt, firstSeat).submit(
            PlayerIntent.Raise(seatIndex = firstSeat, totalAmountThisStreet = raiseTo),
        )
        assertTrue(ack.accepted, "a raise must be accepted over the wire, error=${ack.error}")

        // The raise is applied server-side and reflected to both clients.
        val afterRaise = table.hostGame.nextSnapshot {
            it.currentBetThisStreet == raiseTo && it.actingSeatIndex != null
        }
        assertEquals(raiseTo, afterRaise.currentBetThisStreet, "the raised bet must reach the table")
        table.joinerGame.nextSnapshot { it.currentBetThisStreet == raiseTo }

        // The opponent faces the raise and calls; then play the rest down to a
        // showdown. (Acting on the responder directly keeps the passive driver's
        // forward cursor from stalling on the already-read raise snapshot.)
        val responder = afterRaise.actingSeatIndex!!
        val callAck = table.gameForSeat(afterRaise, responder)
            .submit(PlayerIntent.Call(seatIndex = responder))
        assertTrue(callAck.accepted, "calling the raise must be accepted, error=${callAck.error}")

        val end = table.playPassivelyToCompletion()
        assertEquals(BettingRound.Complete, end.street)
        assertEquals(5, end.community.size, "a called-down raised hand reaches a five-card showdown")
        assertEquals(
            end.settings.startingStack * end.seats.size,
            end.seats.sumOf { it.stack },
            "the pot settles back to the seats — no chips created or destroyed over the wire",
        )
    }

    @Test
    fun foldOnALaterStreet_endsHand_andAwardsPotToTheOtherPlayer() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        // Only preflop fold was covered before; drive to the turn first.
        val turn = table.advancePassivelyUntil(BettingRound.Turn)
        assertEquals(BettingRound.Turn, turn.street)

        val folder = turn.actingSeatIndex!!
        val ack = table.gameForSeat(turn, folder).submit(PlayerIntent.Fold(seatIndex = folder))
        assertTrue(ack.accepted, "a turn fold must be accepted over the wire, error=${ack.error}")

        val end = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        table.joinerGame.nextSnapshot { it.street == BettingRound.Complete }

        val winner = end.seats.first { it.index != folder }
        assertTrue(
            winner.stack > end.settings.startingStack,
            "the non-folder wins the accumulated pot after a turn fold",
        )
        assertEquals(
            end.settings.startingStack * end.seats.size,
            end.seats.sumOf { it.stack },
            "chips conserve through a late-street fold",
        )
    }
}

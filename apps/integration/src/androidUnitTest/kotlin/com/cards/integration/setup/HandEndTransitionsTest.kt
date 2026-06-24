package com.cards.integration.setup

import com.cards.integration.helpers.IntegrationTest
import com.cards.integration.helpers.advancePassivelyUntil
import com.cards.integration.helpers.playPassivelyToCompletion
import com.cards.integration.helpers.seatTwoAndConnect
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * **Hand-end transitions over the wire.** The streets advance correctly, a hand
 * that ends mid-street ends *on that street*, stacks carry from one hand to the
 * next, and a table that can't field two players refuses to start. Complements
 * MultiHandWireTest (which proves the multi-hand chain) with the isolated
 * boundary invariants the matrix flagged as untested.
 */
class HandEndTransitionsTest : IntegrationTest() {

    @Test
    fun handAdvancesThroughStreets_thenEndsOnTheTurnViaBetThenFold() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()

        // Play preflop + flop passively, landing on the turn (4 community cards).
        val turn = table.advancePassivelyUntil(BettingRound.Turn)
        assertEquals(4, turn.community.size, "the turn shows four community cards")

        // On the turn the player to act bets; the other folds → the hand ends here.
        val bettor = turn.actingSeatIndex!!
        val betAck = table.gameForSeat(turn, bettor).submit(PlayerIntent.Bet(bettor, turn.settings.bigBlind))
        assertTrue(betAck.accepted, "a min bet on the turn is legal, error=${betAck.error}")

        val toFold = table.hostGame.nextSnapshot { it.actingSeatIndex != null && it.actingSeatIndex != bettor }
        val folder = toFold.actingSeatIndex!!
        val foldAck = table.gameForSeat(toFold, folder).submit(PlayerIntent.Fold(folder))
        assertTrue(foldAck.accepted, "the facing player folds")

        val complete = table.hostGame.nextSnapshot { it.street == BettingRound.Complete }
        assertEquals(
            4,
            complete.community.size,
            "the hand ended on the turn — never dealt the river",
        )
    }

    @Test
    fun nextHand_carriesEachPlayersFinalStackForward() = integration {
        val table = seatTwoAndConnect()
        table.hostGame.startHand()
        val hand1 = table.playPassivelyToCompletion()

        val hostFinal1 = hand1.seats.first { it.playerId == table.host.userId }.stack
        val joinerFinal1 = hand1.seats.first { it.playerId == table.joiner.userId }.stack
        // A hand conserves chips: the two final stacks sum to the two buy-ins.
        assertEquals(
            hand1.settings.startingStack * 2,
            hostFinal1 + joinerFinal1,
            "chips are conserved within the hand",
        )

        table.hostGame.requestNextHand()
        val hand2 = table.hostGame.nextSnapshot { it.handNumber == 2 && it.actingSeatIndex != null }

        // Each player's hand-2 chips (stack + the blind they just committed) equal
        // their hand-1 final stack — the carry-over is exact, no top-up or reset.
        val hostStart2 = hand2.seats.first { it.playerId == table.host.userId }
            .let { it.stack + it.contributedThisStreet }
        val joinerStart2 = hand2.seats.first { it.playerId == table.joiner.userId }
            .let { it.stack + it.contributedThisStreet }
        assertEquals(hostFinal1, hostStart2, "host carried their exact stack into hand 2")
        assertEquals(joinerFinal1, joinerStart2, "joiner carried their exact stack into hand 2")
    }

    @Test
    fun hostAlone_cannotStartAHand_serverRejects() = integration {
        val host = client()
        val created = assertIs<CreateRoomOutcome.Success>(host.repository.createRoom())
        val hostGame = gameplay(host.connect(created.room.code)).also { it.awaitConnected() }

        val ack = hostGame.startHandAwaitingAck()
        assertFalse(ack.accepted, "a one-player table can't deal a hand — the server rejects StartHand")
    }
}

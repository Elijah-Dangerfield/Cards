package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.usecase.PlayStyleHandSummaryBuilder
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how [PlayStyleHandSummaryBuilder] turns the human's per-hand actions
 * (off the event stream) into the play-style signals the outbox flushes —
 * VPIP / PFR / preflop-fold discipline, aggressive vs call counts, and the
 * showdown-bluff flag. The accumulator is fed in event order then `build()`-ed
 * at HandEnded, mirroring the VM's single ordered collector.
 */
class PlayStyleHandSummaryBuilderTest {

    private val human = 0

    @Test
    fun cleanPreflopFold_marksFoldDiscipline_notVpip() {
        val b = PlayStyleHandSummaryBuilder().apply {
            reset()
            onActionTaken(action(human, PlayerAction.Fold), human)
        }
        val s = b.build(handEnded(), foldedState(), human, XpMode.BOTS)!!
        assertTrue(s.preflopFold)
        assertFalse(s.vpip)
        assertFalse(s.pfr)
        assertEquals(0, s.aggressiveActionCount)
        assertFalse(s.wentToShowdown)
    }

    @Test
    fun preflopRaise_marksVpipAndPfr() {
        val b = PlayStyleHandSummaryBuilder().apply {
            reset()
            onActionTaken(action(human, PlayerAction.Raise(totalStreetContribution = 30, raiseAmount = 20)), human)
        }
        val s = b.build(handEnded(), inHandState(), human, XpMode.BOTS)!!
        assertTrue(s.vpip)
        assertTrue(s.pfr)
        assertFalse(s.preflopFold)
        assertEquals(1, s.aggressiveActionCount)
    }

    @Test
    fun limpThenFold_isVpip_notFoldDiscipline() {
        val b = PlayStyleHandSummaryBuilder().apply {
            reset()
            onActionTaken(action(human, PlayerAction.Call(10)), human)
            onActionTaken(action(human, PlayerAction.Fold), human)
        }
        val s = b.build(handEnded(), foldedState(), human, XpMode.BOTS)!!
        assertTrue(s.vpip)
        assertFalse(s.preflopFold)
        assertEquals(1, s.callActionCount)
    }

    @Test
    fun blindPosted_marksInBlind() {
        val b = PlayStyleHandSummaryBuilder().apply {
            reset()
            onBlindPosted(GameEvent.BlindPosted(sequence = 0, seatIndex = human, amount = 10, isSmall = true), human)
            onActionTaken(action(human, PlayerAction.Fold), human)
        }
        val s = b.build(handEnded(), foldedState(), human, XpMode.BOTS)!!
        assertTrue(s.inBlind)
    }

    @Test
    fun otherSeatsActions_areIgnored() {
        val b = PlayStyleHandSummaryBuilder().apply {
            reset()
            onActionTaken(action(seatIndex = 1, PlayerAction.Raise(30, 20)), human)
            onActionTaken(action(human, PlayerAction.Fold), human)
        }
        val s = b.build(handEnded(), foldedState(), human, XpMode.BOTS)!!
        assertEquals(0, s.aggressiveActionCount)
        assertTrue(s.preflopFold)
    }

    @Test
    fun postflopAggressionIntoShowdown_withWeakHand_andLoss_isBluff() {
        val humanHole = listOf(Card(Rank.Two, Suit.Hearts), Card(Rank.Seven, Suit.Diamonds))
        val board = listOf(
            Card(Rank.King, Suit.Clubs),
            Card(Rank.Nine, Suit.Spades),
            Card(Rank.Four, Suit.Hearts),
            Card(Rank.Jack, Suit.Clubs),
            Card(Rank.Three, Suit.Spades),
        )
        val b = PlayStyleHandSummaryBuilder().apply {
            reset()
            onActionTaken(action(human, PlayerAction.Call(10)), human)
            onStreetAdvanced(GameEvent.StreetAdvanced(0, BettingRound.Flop, board.take(3)))
            onActionTaken(action(human, PlayerAction.Bet(50)), human)
        }
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, isBot = false, playerId = "human")
                    .copy(holeCards = humanHole),
                testSeat(index = 1, isBot = false, playerId = "peer"),
            ),
        )
        val event = GameEvent.HandEnded(
            sequence = 0,
            winners = listOf(HandWinner(seatIndex = 1, amount = 100, handRank = null, byFold = false)),
            board = board,
            revealedHoleCards = mapOf(0 to humanHole),
        )
        val s = b.build(event, state, human, XpMode.BOTS)!!
        assertTrue(s.wentToShowdown)
        assertTrue(s.showdownBluff)
    }

    @Test
    fun showdownWin_isNotBluff() {
        val humanHole = listOf(Card(Rank.Two, Suit.Hearts), Card(Rank.Seven, Suit.Diamonds))
        val board = listOf(
            Card(Rank.King, Suit.Clubs),
            Card(Rank.Nine, Suit.Spades),
            Card(Rank.Four, Suit.Hearts),
        )
        val b = PlayStyleHandSummaryBuilder().apply {
            reset()
            onStreetAdvanced(GameEvent.StreetAdvanced(0, BettingRound.Flop, board))
            onActionTaken(action(human, PlayerAction.Bet(50)), human)
        }
        val state = stubGameState(
            seats = listOf(testSeat(index = 0, isBot = false, playerId = "human").copy(holeCards = humanHole)),
        )
        val event = GameEvent.HandEnded(
            sequence = 0,
            winners = listOf(HandWinner(seatIndex = 0, amount = 100, handRank = null, byFold = false)),
            board = board,
            revealedHoleCards = mapOf(0 to humanHole),
        )
        val s = b.build(event, state, human, XpMode.BOTS)!!
        assertFalse(s.showdownBluff)
    }

    @Test
    fun notDealtIn_returnsNull() {
        val b = PlayStyleHandSummaryBuilder().apply { reset() }
        val state = stubGameState(
            seats = listOf(
                testSeat(index = 0, isBot = false, playerId = "human")
                    .copy(handParticipation = HandParticipation.NotDealt),
            ),
        )
        assertNull(b.build(handEnded(), state, human, XpMode.BOTS))
    }

    // --- helpers ---

    private fun action(seatIndex: Int, action: PlayerAction) =
        GameEvent.ActionTaken(sequence = 0, seatIndex = seatIndex, action = action, resultingStreetContribution = 0)

    private fun handEnded() = GameEvent.HandEnded(
        sequence = 0,
        winners = emptyList(),
        board = emptyList(),
        revealedHoleCards = emptyMap(),
    )

    private fun inHandState() = stubGameState(
        seats = listOf(testSeat(index = 0, isBot = false, playerId = "human")),
    )

    private fun foldedState() = stubGameState(
        seats = listOf(
            testSeat(index = 0, isBot = false, playerId = "human")
                .copy(handParticipation = HandParticipation.Folded),
        ),
    )
}

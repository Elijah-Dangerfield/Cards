package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.HandResultView
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandWinner
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the felt-showdown board highlight: only the board cards that compose the
 * winning hand light up, the rest dim. Guards the "dim the loser, light the
 * winner" payoff against a regression in the bestFive intersection.
 */
class BoardShowdownTest {

    private fun card(rank: Rank, suit: Suit) = Card(rank, suit)

    @Test
    fun lightsOnlyTheBoardCardsInTheWinningHand() {
        val board = listOf(
            card(Rank.Ten, Suit.Hearts),
            card(Rank.Jack, Suit.Hearts),
            card(Rank.Queen, Suit.Hearts),
            card(Rank.Three, Suit.Clubs),
            card(Rank.Seven, Suit.Spades),
        )
        // Winner holds Ah Kh — a royal flush in hearts uses the three heart board
        // cards; the off-suit 3 and 7 are not part of the winning five.
        val winnerHole = listOf(card(Rank.Ace, Suit.Hearts), card(Rank.King, Suit.Hearts))
        val result = HandResultView(
            winners = listOf(
                HandWinner(
                    seatIndex = 0,
                    amount = 480,
                    handRank = HandEvaluator.evaluate(winnerHole + board),
                    byFold = false,
                ),
            ),
            board = board,
        )

        val lit = winningBoardCards(result, board)

        assertEquals(
            setOf(
                card(Rank.Ten, Suit.Hearts),
                card(Rank.Jack, Suit.Hearts),
                card(Rank.Queen, Suit.Hearts),
            ),
            lit,
            "only the heart board cards in the royal flush light up",
        )
    }

    @Test
    fun winByFold_lightsNothing() {
        val board = listOf(
            card(Rank.Two, Suit.Clubs),
            card(Rank.Nine, Suit.Diamonds),
            card(Rank.King, Suit.Spades),
        )
        val result = HandResultView(
            winners = listOf(HandWinner(seatIndex = 0, amount = 30, handRank = null, byFold = true)),
            board = board,
        )

        assertTrue(
            winningBoardCards(result, board).isEmpty(),
            "a fold win has no showdown, so no board card is highlighted",
        )
    }

    @Test
    fun noResultYet_lightsNothing() {
        assertTrue(winningBoardCards(handResult = null, communityCards = emptyList()).isEmpty())
    }
}

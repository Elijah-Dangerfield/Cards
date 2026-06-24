package com.dangerfield.cards.libraries.gameplay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandEvaluatorTest {

    private fun hand(vararg cards: String): List<Card> = cards.map { Card.parse(it) }

    private fun eval(vararg cards: String): HandRank =
        HandEvaluator.evaluate(hand(*cards))

    @Test
    fun royalFlushDetected() {
        val r = eval("A♠", "K♠", "Q♠", "J♠", "T♠")
        assertEquals(HandCategory.RoyalFlush, r.category)
        assertEquals(listOf(14), r.tiebreakers)
    }

    @Test
    fun straightFlushDetected() {
        val r = eval("9♥", "8♥", "7♥", "6♥", "5♥")
        assertEquals(HandCategory.StraightFlush, r.category)
        assertEquals(listOf(9), r.tiebreakers)
    }

    @Test
    fun wheelStraightFlush() {
        val r = eval("A♠", "2♠", "3♠", "4♠", "5♠")
        assertEquals(HandCategory.StraightFlush, r.category)
        assertEquals(listOf(5), r.tiebreakers)
    }

    @Test
    fun fourOfAKindDetected() {
        val r = eval("Q♠", "Q♥", "Q♦", "Q♣", "9♠")
        assertEquals(HandCategory.FourOfAKind, r.category)
        assertEquals(listOf(12, 9), r.tiebreakers)
    }

    @Test
    fun fourOfAKindKickerMatters() {
        val higher = eval("7♠", "7♥", "7♦", "7♣", "A♠")
        val lower = eval("7♠", "7♥", "7♦", "7♣", "K♠")
        assertTrue(higher > lower)
    }

    @Test
    fun fullHouseDetected() {
        val r = eval("K♠", "K♥", "K♦", "3♣", "3♠")
        assertEquals(HandCategory.FullHouse, r.category)
        assertEquals(listOf(13, 3), r.tiebreakers)
    }

    @Test
    fun fullHouseBeatenByHigherTrips() {
        val kingsFull = eval("K♠", "K♥", "K♦", "3♣", "3♠")
        val acesFull = eval("A♠", "A♥", "A♦", "2♣", "2♠")
        assertTrue(acesFull > kingsFull)
    }

    @Test
    fun fullHouseTripsTiePairBreaks() {
        val kingsOverThrees = eval("K♠", "K♥", "K♦", "3♣", "3♠")
        val kingsOverTwos = eval("K♠", "K♥", "K♦", "2♣", "2♠")
        assertTrue(kingsOverThrees > kingsOverTwos)
    }

    @Test
    fun flushDetected() {
        val r = eval("A♣", "T♣", "8♣", "5♣", "2♣")
        assertEquals(HandCategory.Flush, r.category)
        assertEquals(listOf(14, 10, 8, 5, 2), r.tiebreakers)
    }

    @Test
    fun flushTieBreaksOnHighest() {
        val acesHigh = eval("A♣", "T♣", "8♣", "5♣", "2♣")
        val kingsHigh = eval("K♣", "T♣", "8♣", "5♣", "2♣")
        assertTrue(acesHigh > kingsHigh)
    }

    @Test
    fun straightDetected() {
        val r = eval("9♣", "8♦", "7♥", "6♠", "5♣")
        assertEquals(HandCategory.Straight, r.category)
        assertEquals(listOf(9), r.tiebreakers)
    }

    @Test
    fun wheelStraightTreatsAceAsLow() {
        val wheel = eval("A♣", "2♦", "3♥", "4♠", "5♣")
        assertEquals(HandCategory.Straight, wheel.category)
        assertEquals(listOf(5), wheel.tiebreakers)
        val six = eval("2♣", "3♦", "4♥", "5♠", "6♣")
        assertTrue(six > wheel)
    }

    @Test
    fun broadwayStraight() {
        val r = eval("A♣", "K♦", "Q♥", "J♠", "T♣")
        assertEquals(HandCategory.Straight, r.category)
        assertEquals(listOf(14), r.tiebreakers)
    }

    @Test
    fun queenHighStraightVsKingHigh() {
        val q = eval("Q♣", "J♦", "T♥", "9♠", "8♣")
        val k = eval("K♣", "Q♦", "J♥", "T♠", "9♣")
        assertTrue(k > q)
    }

    @Test
    fun threeOfAKindDetected() {
        val r = eval("9♠", "9♥", "9♦", "K♣", "2♠")
        assertEquals(HandCategory.ThreeOfAKind, r.category)
        assertEquals(listOf(9, 13, 2), r.tiebreakers)
    }

    @Test
    fun threeOfAKindKickerOrdering() {
        val higher = eval("9♠", "9♥", "9♦", "A♣", "2♠")
        val lower = eval("9♠", "9♥", "9♦", "K♣", "Q♠")
        assertTrue(higher > lower)
    }

    @Test
    fun twoPairDetected() {
        val r = eval("A♠", "A♥", "K♦", "K♣", "3♠")
        assertEquals(HandCategory.TwoPair, r.category)
        assertEquals(listOf(14, 13, 3), r.tiebreakers)
    }

    @Test
    fun twoPairKickerMatters() {
        val higherKicker = eval("A♠", "A♥", "K♦", "K♣", "Q♠")
        val lowerKicker = eval("A♠", "A♥", "K♦", "K♣", "3♠")
        assertTrue(higherKicker > lowerKicker)
    }

    @Test
    fun twoPairTopPairTrumps() {
        val acesTwos = eval("A♠", "A♥", "2♦", "2♣", "K♠")
        val kingsQueens = eval("K♠", "K♥", "Q♦", "Q♣", "2♠")
        assertTrue(acesTwos > kingsQueens)
    }

    @Test
    fun pairDetected() {
        val r = eval("8♠", "8♥", "K♦", "5♣", "2♠")
        assertEquals(HandCategory.Pair, r.category)
        assertEquals(listOf(8, 13, 5, 2), r.tiebreakers)
    }

    @Test
    fun pairKickerOrdering() {
        val higher = eval("8♠", "8♥", "A♦", "5♣", "2♠")
        val lower = eval("8♠", "8♥", "K♦", "5♣", "2♠")
        assertTrue(higher > lower)
    }

    @Test
    fun highCardDetected() {
        val r = eval("A♠", "Q♥", "9♦", "5♣", "2♠")
        assertEquals(HandCategory.HighCard, r.category)
        assertEquals(listOf(14, 12, 9, 5, 2), r.tiebreakers)
    }

    @Test
    fun highCardOrderingAcrossKickers() {
        val higher = eval("A♠", "Q♥", "9♦", "5♣", "2♠")
        val lower = eval("A♠", "Q♥", "9♦", "4♣", "3♠")
        assertTrue(higher > lower)
    }

    @Test
    fun categoryOrderingTotalOrder() {
        val highCard = eval("A♠", "Q♥", "9♦", "5♣", "2♠")
        val pair = eval("2♠", "2♥", "K♦", "5♣", "3♠")
        val twoPair = eval("3♠", "3♥", "2♦", "2♣", "K♠")
        val trips = eval("3♠", "3♥", "3♦", "K♣", "Q♠")
        val straight = eval("5♠", "6♥", "7♦", "8♣", "9♠")
        val flush = eval("2♣", "5♣", "9♣", "J♣", "K♣")
        val full = eval("3♠", "3♥", "3♦", "2♣", "2♠")
        val quads = eval("3♠", "3♥", "3♦", "3♣", "2♠")
        val sf = eval("5♠", "6♠", "7♠", "8♠", "9♠")
        val royal = eval("T♠", "J♠", "Q♠", "K♠", "A♠")

        assertTrue(highCard < pair)
        assertTrue(pair < twoPair)
        assertTrue(twoPair < trips)
        assertTrue(trips < straight)
        assertTrue(straight < flush)
        assertTrue(flush < full)
        assertTrue(full < quads)
        assertTrue(quads < sf)
        assertTrue(sf < royal)
    }

    @Test
    fun sevenCardPicksBestFive() {
        val r = HandEvaluator.evaluate(hand("A♠", "K♠", "Q♠", "J♠", "T♠", "2♥", "3♣"))
        assertEquals(HandCategory.RoyalFlush, r.category)
    }

    @Test
    fun sevenCardFullHouseFromBoard() {
        val r = HandEvaluator.evaluate(hand("K♠", "K♥", "8♦", "8♣", "8♠", "2♣", "3♦"))
        assertEquals(HandCategory.FullHouse, r.category)
        assertEquals(listOf(8, 13), r.tiebreakers)
    }

    @Test
    fun sevenCardFlushUsesHighestFive() {
        val r = HandEvaluator.evaluate(hand("A♣", "K♣", "Q♣", "9♣", "5♣", "2♣", "3♦"))
        assertEquals(HandCategory.Flush, r.category)
        assertEquals(listOf(14, 13, 12, 9, 5), r.tiebreakers)
    }

    @Test
    fun sevenCardCounterfeitedTwoPair() {
        val r = HandEvaluator.evaluate(hand("A♠", "A♥", "2♦", "2♣", "3♠", "3♥", "K♣"))
        assertEquals(HandCategory.TwoPair, r.category)
        assertEquals(listOf(14, 3, 13), r.tiebreakers)
    }

    @Test
    fun sevenCardWheelStraight() {
        val r = HandEvaluator.evaluate(hand("A♠", "2♥", "3♦", "4♣", "5♠", "K♣", "Q♦"))
        assertEquals(HandCategory.Straight, r.category)
        assertEquals(listOf(5), r.tiebreakers)
    }

    @Test
    fun sevenCardStraightVsHigherStraight() {
        val r = HandEvaluator.evaluate(hand("8♥", "9♦", "T♣", "J♠", "Q♥", "2♠", "3♦"))
        assertEquals(HandCategory.Straight, r.category)
        assertEquals(listOf(12), r.tiebreakers)
    }

    @Test
    fun sevenCardStraightFlushBeatsRegularFlush() {
        val r = HandEvaluator.evaluate(hand("5♥", "6♥", "7♥", "8♥", "9♥", "A♥", "2♣"))
        assertEquals(HandCategory.StraightFlush, r.category)
        assertEquals(listOf(9), r.tiebreakers)
    }

    @Test
    fun sevenCardQuadsWithKicker() {
        val r = HandEvaluator.evaluate(hand("Q♠", "Q♥", "Q♦", "Q♣", "9♠", "A♣", "3♦"))
        assertEquals(HandCategory.FourOfAKind, r.category)
        assertEquals(listOf(12, 14), r.tiebreakers)
    }

    @Test
    fun splitPotEqualHands() {
        val a = HandEvaluator.evaluate(hand("A♠", "K♥", "Q♦", "J♣", "T♠", "2♣", "3♦"))
        val b = HandEvaluator.evaluate(hand("A♥", "K♦", "Q♣", "J♠", "T♥", "2♥", "3♥"))
        assertEquals(0, a.compareTo(b))
    }

    @Test
    fun duplicateCardsThrows() {
        assertFailsWith<IllegalArgumentException> {
            HandEvaluator.evaluate(hand("A♠", "A♠", "K♦", "Q♣", "J♠"))
        }
    }

    @Test
    fun tooFewCardsThrows() {
        assertFailsWith<IllegalArgumentException> {
            HandEvaluator.evaluate(hand("A♠", "K♦", "Q♣", "J♠"))
        }
    }

    @Test
    fun tooManyCardsThrows() {
        assertFailsWith<IllegalArgumentException> {
            HandEvaluator.evaluate(hand("A♠", "K♦", "Q♣", "J♠", "T♥", "9♥", "8♥", "7♥"))
        }
    }

    @Test
    fun evaluateOrNull_returnsNullOnDuplicate_insteadOfThrowing() {
        // MP-4: the exact 7-card set that crashed the play screen at showdown
        // (CARDS-2Q) — a board+hole merge that duplicated the 8 of spades after
        // a snapshot landed over stale local cards. The display/projection path
        // must degrade to null, never throw.
        val crashSet = hand("8♠", "2♥", "8♣", "Q♠", "8♠", "A♣", "6♥")
        assertNull(HandEvaluator.evaluateOrNull(crashSet))
    }

    @Test
    fun evaluateOrNull_returnsNullOnWrongCardCount() {
        assertNull(HandEvaluator.evaluateOrNull(hand("A♠", "K♦", "Q♣", "J♠")))
        assertNull(
            HandEvaluator.evaluateOrNull(
                hand("A♠", "K♦", "Q♣", "J♠", "T♥", "9♥", "8♥", "7♥"),
            ),
        )
    }

    @Test
    fun evaluateOrNull_matchesEvaluateOnValidHand() {
        val cards = hand("A♠", "A♥", "K♦", "K♣", "3♠", "2♣", "7♥")
        assertEquals(HandEvaluator.evaluate(cards), HandEvaluator.evaluateOrNull(cards))
    }

    @Test
    fun parseAcceptsLetterSuits() {
        assertEquals(Card(Rank.Ace, Suit.Spades), Card.parse("As"))
        assertEquals(Card(Rank.King, Suit.Hearts), Card.parse("Kh"))
        assertEquals(Card(Rank.Ten, Suit.Clubs), Card.parse("Tc"))
        assertEquals(Card(Rank.Two, Suit.Diamonds), Card.parse("2d"))
    }

    @Test
    fun bestFiveContainsExactlyFiveCards() {
        val r = HandEvaluator.evaluate(hand("A♠", "A♥", "K♦", "K♣", "3♠", "2♣", "7♥"))
        assertEquals(5, r.bestFive.size)
        assertEquals(5, r.bestFive.toSet().size)
    }

    @Test
    fun straightDoesNotWrapAround() {
        val r = eval("Q♣", "K♦", "A♥", "2♠", "3♣")
        assertEquals(HandCategory.HighCard, r.category)
    }
}

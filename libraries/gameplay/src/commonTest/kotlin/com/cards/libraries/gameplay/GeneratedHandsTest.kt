package com.dangerfield.cards.libraries.gameplay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneratedHandsTest {

    @Test
    fun everyRoyalFlush() {
        var count = 0
        for (suit in Suit.all) {
            val hand = listOf(Rank.Ace, Rank.King, Rank.Queen, Rank.Jack, Rank.Ten)
                .map { Card(it, suit) }
            val r = HandEvaluator.evaluate(hand)
            assertEquals(HandCategory.RoyalFlush, r.category, "suit=$suit")
            assertEquals(listOf(14), r.tiebreakers)
            count++
        }
        assertEquals(4, count)
    }

    @Test
    fun everyStraightFlush() {
        val lowestStarts = listOf(2, 3, 4, 5, 6, 7, 8, 9)
        var count = 0
        for (suit in Suit.all) {
            for (low in lowestStarts) {
                val hand = (low..low + 4).map { Card(Rank.fromValue(it), suit) }
                val r = HandEvaluator.evaluate(hand)
                assertEquals(HandCategory.StraightFlush, r.category, "suit=$suit low=$low")
                assertEquals(listOf(low + 4), r.tiebreakers)
                count++
            }
            val wheel = listOf(Rank.Ace, Rank.Two, Rank.Three, Rank.Four, Rank.Five)
                .map { Card(it, suit) }
            val rw = HandEvaluator.evaluate(wheel)
            assertEquals(HandCategory.StraightFlush, rw.category)
            assertEquals(listOf(5), rw.tiebreakers)
            count++
        }
        assertEquals(4 * 9, count)
    }

    @Test
    fun everyFourOfAKindWithEveryKicker() {
        var count = 0
        for (quadRank in Rank.all) {
            for (kickerRank in Rank.all) {
                if (kickerRank == quadRank) continue
                for (kickerSuit in Suit.all) {
                    val hand = Suit.all.map { Card(quadRank, it) } + Card(kickerRank, kickerSuit)
                    val r = HandEvaluator.evaluate(hand)
                    assertEquals(HandCategory.FourOfAKind, r.category, "quads=$quadRank kicker=$kickerRank")
                    assertEquals(quadRank.value, r.tiebreakers[0])
                    assertEquals(kickerRank.value, r.tiebreakers[1])
                    count++
                }
            }
        }
        assertEquals(13 * 12 * 4, count)
    }

    @Test
    fun fullHouseSampling() {
        var count = 0
        for (tripsRank in Rank.all) {
            for (pairRank in Rank.all) {
                if (pairRank == tripsRank) continue
                val tripsSuits = Suit.all.take(3)
                val pairSuits = Suit.all.take(2)
                val hand = tripsSuits.map { Card(tripsRank, it) } +
                    pairSuits.map { Card(pairRank, it) }
                val r = HandEvaluator.evaluate(hand)
                assertEquals(HandCategory.FullHouse, r.category)
                assertEquals(listOf(tripsRank.value, pairRank.value), r.tiebreakers)
                count++
            }
        }
        assertEquals(13 * 12, count)
    }

    @Test
    fun straightAtEveryHigh() {
        for (high in 5..14) {
            val ranks = if (high == 5) {
                listOf(Rank.Five, Rank.Four, Rank.Three, Rank.Two, Rank.Ace)
            } else {
                (high downTo high - 4).map { Rank.fromValue(it) }
            }
            val hand = listOf(
                Card(ranks[0], Suit.Clubs),
                Card(ranks[1], Suit.Diamonds),
                Card(ranks[2], Suit.Hearts),
                Card(ranks[3], Suit.Spades),
                Card(ranks[4], Suit.Clubs),
            )
            if (hand.distinct().size != 5) continue
            val r = HandEvaluator.evaluate(hand)
            assertEquals(HandCategory.Straight, r.category, "high=$high")
            assertEquals(listOf(high), r.tiebreakers)
        }
    }

    @Test
    fun randomizedSevenCardConsistency() {
        val rng = Random(0xC0FFEEL)
        repeat(2_000) {
            val deck = Card.fullDeck.toMutableList()
            deck.shuffle(rng)
            val seven = deck.take(7)
            val rank = HandEvaluator.evaluate(seven)
            val rankAgain = HandEvaluator.evaluate(seven.reversed())
            assertEquals(0, rank.compareTo(rankAgain), "evaluation must be order-independent")
            assertEquals(5, rank.bestFive.size)
            assertEquals(5, rank.bestFive.toSet().size)
            assertTrue(rank.bestFive.all { it in seven }, "best five must be a subset of input")
        }
    }

    @Test
    fun strictOrderingAcrossManyRandomPairs() {
        val rng = Random(0xBEEFL)
        repeat(2_000) {
            val deck = Card.fullDeck.toMutableList()
            deck.shuffle(rng)
            val a = HandEvaluator.evaluate(deck.subList(0, 7))
            val b = HandEvaluator.evaluate(deck.subList(7, 14))
            assertEquals(a.compareTo(b), -b.compareTo(a))
        }
    }

    @Test
    fun handCategoryOrdinalsLineUp() {
        assertTrue(HandCategory.HighCard.ordinal < HandCategory.Pair.ordinal)
        assertTrue(HandCategory.Pair.ordinal < HandCategory.TwoPair.ordinal)
        assertTrue(HandCategory.TwoPair.ordinal < HandCategory.ThreeOfAKind.ordinal)
        assertTrue(HandCategory.ThreeOfAKind.ordinal < HandCategory.Straight.ordinal)
        assertTrue(HandCategory.Straight.ordinal < HandCategory.Flush.ordinal)
        assertTrue(HandCategory.Flush.ordinal < HandCategory.FullHouse.ordinal)
        assertTrue(HandCategory.FullHouse.ordinal < HandCategory.FourOfAKind.ordinal)
        assertTrue(HandCategory.FourOfAKind.ordinal < HandCategory.StraightFlush.ordinal)
        assertTrue(HandCategory.StraightFlush.ordinal < HandCategory.RoyalFlush.ordinal)
    }

    @Test
    fun wheelDoesNotBeatRegularStraight() {
        val wheel = HandEvaluator.evaluate(
            listOf(
                Card(Rank.Ace, Suit.Clubs),
                Card(Rank.Two, Suit.Diamonds),
                Card(Rank.Three, Suit.Hearts),
                Card(Rank.Four, Suit.Spades),
                Card(Rank.Five, Suit.Clubs),
            )
        )
        for (high in 6..14) {
            val ranks = (high downTo high - 4).map { Rank.fromValue(it) }
            val hand = listOf(
                Card(ranks[0], Suit.Clubs),
                Card(ranks[1], Suit.Diamonds),
                Card(ranks[2], Suit.Hearts),
                Card(ranks[3], Suit.Spades),
                Card(ranks[4], Suit.Clubs),
            )
            if (hand.distinct().size != 5) continue
            val higher = HandEvaluator.evaluate(hand)
            assertTrue(higher > wheel, "$high-high straight must beat the wheel")
            assertNotEquals(0, higher.compareTo(wheel))
        }
    }
}

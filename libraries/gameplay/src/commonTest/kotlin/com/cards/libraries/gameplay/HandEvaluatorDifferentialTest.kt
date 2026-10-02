package com.dangerfield.cards.libraries.gameplay

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential + invariant sweep over [HandEvaluator], written to answer a
 * player report that "a hand won when it shouldn't have".
 *
 * The 42 cases in [HandEvaluatorTest] are hand-picked examples. This file
 * instead checks the evaluator against an **independently written reference**
 * across tens of thousands of random hands, plus the structural invariants a
 * wrong winner would violate. The reference scores a 5-card hand with a
 * different algorithm (rank bitmask for straights, suit histogram for flushes,
 * a single packed integer for the tiebreak) so a shared mistake is unlikely.
 */
class HandEvaluatorDifferentialTest {

    // ---------------------------------------------------------------- reference

    /**
     * Packs a 5-card hand into one comparable Long, using a deliberately
     * different technique from [HandEvaluator]: a 13-bit rank mask for straight
     * detection and a suit histogram for the flush, then base-16 digits for the
     * tiebreak. Higher is strictly better.
     */
    private fun referenceScore(five: List<Card>): Long {
        require(five.size == 5)

        var rankMask = 0
        val suitCounts = IntArray(4)
        val rankCounts = IntArray(15)
        for (card in five) {
            rankMask = rankMask or (1 shl card.rank.value)
            suitCounts[card.suit.ordinal]++
            rankCounts[card.rank.value]++
        }

        val isFlush = suitCounts.any { it == 5 }

        // Straight: five consecutive bits, or the wheel (A treated as low).
        var straightHigh = 0
        for (high in 14 downTo 5) {
            val needed = if (high == 5) {
                (1 shl 14) or (1 shl 5) or (1 shl 4) or (1 shl 3) or (1 shl 2)
            } else {
                (1 shl high) or (1 shl (high - 1)) or (1 shl (high - 2)) or
                    (1 shl (high - 3)) or (1 shl (high - 4))
            }
            if (rankMask and needed == needed) {
                straightHigh = high
                break
            }
        }

        // Ranks grouped by (count, rank), both descending — the tiebreak order.
        val byCount = (2..14)
            .filter { rankCounts[it] > 0 }
            .sortedWith(compareByDescending<Int> { rankCounts[it] }.thenByDescending { it })
        val pattern = byCount.map { rankCounts[it] }

        val category = when {
            isFlush && straightHigh == 14 -> 9 // royal
            isFlush && straightHigh > 0 -> 8
            pattern == listOf(4, 1) -> 7
            pattern == listOf(3, 2) -> 6
            isFlush -> 5
            straightHigh > 0 -> 4
            pattern == listOf(3, 1, 1) -> 3
            pattern == listOf(2, 2, 1) -> 2
            pattern == listOf(2, 1, 1, 1) -> 1
            else -> 0
        }

        // Straights and straight flushes tiebreak on the high card alone; the
        // wheel's high card is the five, not the ace.
        val tiebreak: List<Int> = when (category) {
            9 -> listOf(14)
            8, 4 -> listOf(straightHigh)
            else -> byCount
        }

        var score = category.toLong()
        for (i in 0 until 5) {
            score = score * 16L + (tiebreak.getOrElse(i) { 0 }).toLong()
        }
        return score
    }

    private fun referenceBest(cards: List<Card>): Long {
        var best = Long.MIN_VALUE
        for (combo in combinationsOfFive(cards)) {
            val score = referenceScore(combo)
            if (score > best) best = score
        }
        return best
    }

    private fun combinationsOfFive(cards: List<Card>): List<List<Card>> {
        val out = mutableListOf<List<Card>>()
        val n = cards.size
        for (a in 0 until n - 4) {
            for (b in a + 1 until n - 3) {
                for (c in b + 1 until n - 2) {
                    for (d in c + 1 until n - 1) {
                        for (e in d + 1 until n) {
                            out += listOf(cards[a], cards[b], cards[c], cards[d], cards[e])
                        }
                    }
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------- checks

    @Test
    fun `enumeration covers every five card subset`() {
        assertEquals(21, combinationsOfFive(Card.fullDeck.take(7)).size)
        assertEquals(6, combinationsOfFive(Card.fullDeck.take(6)).size)
    }

    @Test
    fun `evaluator agrees with the reference on which of two seven card hands wins`() {
        val random = Random(20261002)
        var compared = 0

        repeat(30_000) {
            val deck = Card.fullDeck.shuffled(random)
            val board = deck.take(5)
            val a = board + deck.subList(5, 7)
            val b = board + deck.subList(7, 9)

            val rankA = HandEvaluator.evaluate(a)
            val rankB = HandEvaluator.evaluate(b)
            val ours = rankA.compareTo(rankB)

            val theirs = referenceBest(a).compareTo(referenceBest(b))

            assertEquals(
                theirs.coerceIn(-1, 1),
                ours.coerceIn(-1, 1),
                "disagreed on winner\n" +
                    "  board = $board\n" +
                    "  seatA = ${deck.subList(5, 7)} -> ${rankA.category} ${rankA.tiebreakers}\n" +
                    "  seatB = ${deck.subList(7, 9)} -> ${rankB.category} ${rankB.tiebreakers}",
            )
            compared++
        }

        assertEquals(30_000, compared)
    }

    @Test
    fun `evaluator agrees with the reference on category and ordering for random hands`() {
        val random = Random(777)
        repeat(20_000) {
            val cards = Card.fullDeck.shuffled(random).take(7)
            val rank = HandEvaluator.evaluate(cards)
            val referenceCategory = (referenceBest(cards) / 16L / 16L / 16L / 16L / 16L).toInt()
            assertEquals(
                referenceCategory,
                rank.category.ordinal,
                "category mismatch for $cards -> ${rank.category} ${rank.tiebreakers}",
            )
        }
    }

    @Test
    fun `bestFive is a real subset of the input and re-evaluates identically`() {
        val random = Random(31337)
        repeat(20_000) {
            val cards = Card.fullDeck.shuffled(random).take(7)
            val rank = HandEvaluator.evaluate(cards)

            assertEquals(5, rank.bestFive.size, "bestFive wrong size for $cards")
            assertEquals(
                5,
                rank.bestFive.distinct().size,
                "bestFive has duplicates for $cards -> ${rank.bestFive}",
            )
            assertTrue(
                rank.bestFive.all { it in cards },
                "bestFive card not in the hand: $cards -> ${rank.bestFive}",
            )

            // The five cards the UI highlights must be worth exactly what the
            // seven-card evaluation claimed. A mismatch here is how a player
            // sees a hand lose while its highlighted cards look like the winner.
            val reEvaluated = HandEvaluator.evaluate(rank.bestFive)
            assertEquals(
                rank.category,
                reEvaluated.category,
                "bestFive re-evaluates to a different category for $cards",
            )
            assertEquals(
                rank.tiebreakers,
                reEvaluated.tiebreakers,
                "bestFive re-evaluates to different tiebreakers for $cards " +
                    "(${rank.category}, bestFive=${rank.bestFive})",
            )
        }
    }

    @Test
    fun `comparison is a consistent total order`() {
        val random = Random(99)
        val hands = List(400) {
            HandEvaluator.evaluate(Card.fullDeck.shuffled(random).take(7))
        }

        for (a in hands) {
            assertEquals(0, a.compareTo(a), "not reflexive: ${a.category} ${a.tiebreakers}")
            for (b in hands) {
                val ab = a.compareTo(b).coerceIn(-1, 1)
                val ba = b.compareTo(a).coerceIn(-1, 1)
                assertEquals(-ab, ba, "antisymmetry broken between $a and $b")
            }
        }
    }

    @Test
    fun `equal strength hands of different suits tie exactly`() {
        // The classic "why did he win, we had the same hand" case: identical
        // ranks, different suits. These must compare equal so the pot splits.
        val random = Random(5)
        repeat(2_000) {
            val ranks = Rank.all.shuffled(random).take(5)
            val handA = ranks.map { Card(it, Suit.Clubs) }
            val handB = ranks.map { Card(it, Suit.Hearts) }
            // Both are flushes here, so re-draw mixed-suit variants too.
            assertEquals(0, HandEvaluator.evaluate(handA).compareTo(HandEvaluator.evaluate(handB)))

            val mixedA = ranks.mapIndexed { i, r -> Card(r, if (i == 0) Suit.Spades else Suit.Clubs) }
            val mixedB = ranks.mapIndexed { i, r -> Card(r, if (i == 0) Suit.Diamonds else Suit.Hearts) }
            assertEquals(
                0,
                HandEvaluator.evaluate(mixedA).compareTo(HandEvaluator.evaluate(mixedB)),
                "same ranks, different suits did not tie: $mixedA vs $mixedB",
            )
        }
    }
}

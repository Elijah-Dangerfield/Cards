package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.Card
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandStrengthTest {

    private fun hole(a: String, b: String): List<Card> = listOf(Card.parse(a), Card.parse(b))

    @Test
    fun pocketAcesIsStrongestPair() {
        val aa = HandStrength.chenScore(hole("As", "Ah"))
        val kk = HandStrength.chenScore(hole("Ks", "Kh"))
        val twos = HandStrength.chenScore(hole("2s", "2h"))
        assertTrue(aa > kk)
        assertTrue(kk > twos)
    }

    @Test
    fun suitedConnectorsBeatOffsuitGappers() {
        val suited = HandStrength.chenScore(hole("Ts", "9s"))
        val offsuit = HandStrength.chenScore(hole("Ts", "7d"))
        assertTrue(suited > offsuit)
    }

    @Test
    fun normalizedStrengthIsBetweenZeroAndOne() {
        val sample = listOf(
            hole("As", "Ah"),
            hole("7s", "2c"),
            hole("Ts", "9s"),
            hole("Kc", "Ks"),
        )
        for (h in sample) {
            val s = HandStrength.normalizedPreflopStrength(h)
            assertTrue(s in 0.0..1.0, "out of range: $s for $h")
        }
    }

    @Test
    fun equityAgainstSingleOpponentInvariants() {
        val rng = Random(1L)
        val aa = HandStrength.equityVsRandom(
            holeCards = hole("As", "Ah"),
            community = emptyList(),
            numOpponents = 1,
            iterations = 600,
            random = rng,
        )
        val seventwo = HandStrength.equityVsRandom(
            holeCards = hole("7s", "2c"),
            community = emptyList(),
            numOpponents = 1,
            iterations = 600,
            random = rng,
        )
        assertTrue(aa > 0.7, "AA equity unexpectedly low: $aa")
        assertTrue(seventwo < 0.5, "7-2 equity unexpectedly high: $seventwo")
        assertTrue(aa > seventwo)
    }

    @Test
    fun equityBreakdownBucketsSumToHundred() {
        val rng = Random(1L)
        val breakdown = HandStrength.equityBreakdownVsRandom(
            holeCards = hole("As", "Ah"),
            community = emptyList(),
            numOpponents = 1,
            iterations = 600,
            random = rng,
        )
        assertEquals(100, breakdown.winPct + breakdown.tiePct + breakdown.losePct)
        assertTrue(breakdown.winPct > 70, "AA win% unexpectedly low: ${breakdown.winPct}")
        assertTrue(breakdown.losePct < 30, "AA lose% unexpectedly high: ${breakdown.losePct}")
    }

    @Test
    fun equityBreakdownFavorsStrongerHand() {
        val rng = Random(2L)
        val aa = HandStrength.equityBreakdownVsRandom(
            holeCards = hole("As", "Ah"),
            community = emptyList(),
            numOpponents = 1,
            iterations = 400,
            random = rng,
        )
        val seventwo = HandStrength.equityBreakdownVsRandom(
            holeCards = hole("7s", "2c"),
            community = emptyList(),
            numOpponents = 1,
            iterations = 400,
            random = rng,
        )
        assertTrue(aa.winPct > seventwo.winPct)
        assertTrue(aa.losePct < seventwo.losePct)
    }

    @Test
    fun flushDrawDetected() {
        val hole = hole("As", "Ks")
        val board = listOf(Card.parse("9s"), Card.parse("4s"), Card.parse("2c"))
        val draws = HandStrength.drawPotential(hole, board)
        assertTrue(draws.flushDraw)
    }

    @Test
    fun openEndedStraightDrawDetected() {
        val hole = hole("9h", "8s")
        val board = listOf(Card.parse("7c"), Card.parse("6d"), Card.parse("2h"))
        val draws = HandStrength.drawPotential(hole, board)
        assertTrue(draws.openEndedStraight)
    }

    @Test
    fun gutshotDetected() {
        val hole = hole("9h", "5s")
        val board = listOf(Card.parse("7c"), Card.parse("6d"), Card.parse("2h"))
        val draws = HandStrength.drawPotential(hole, board)
        assertTrue(draws.gutshot)
    }

    @Test
    fun noDrawOnUnrelatedBoard() {
        val hole = hole("Ah", "Kh")
        val board = listOf(Card.parse("2c"), Card.parse("7d"), Card.parse("Js"))
        val draws = HandStrength.drawPotential(hole, board)
        assertEquals(false, draws.hasDraw)
    }
}

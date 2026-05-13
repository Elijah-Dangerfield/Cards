package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import kotlinx.serialization.Serializable
import kotlin.random.Random

object HandStrength {

    fun chenScore(holeCards: List<Card>): Double {
        require(holeCards.size == 2) { "chenScore requires exactly 2 hole cards" }
        val (high, low) = holeCards.sortedByDescending { it.rank.value }.let { it[0] to it[1] }
        val highBase = chenBase(high.rank)
        val isPair = high.rank == low.rank
        if (isPair) {
            val base = (highBase * 2).coerceAtLeast(5.0)
            return base
        }
        var score = highBase
        if (high.suit == low.suit) score += 2.0
        val gap = high.rank.value - low.rank.value - 1
        score += when (gap) {
            0 -> 0.0
            1 -> -1.0
            2 -> -2.0
            3 -> -4.0
            else -> -5.0
        }
        if (gap <= 1 && high.rank.value < Rank.Queen.value) score += 1.0
        return score
    }

    fun normalizedPreflopStrength(holeCards: List<Card>): Double {
        val score = chenScore(holeCards)
        return ((score + 5.0) / 25.0).coerceIn(0.0, 1.0)
    }

    fun equityVsRandom(
        holeCards: List<Card>,
        community: List<Card>,
        numOpponents: Int,
        iterations: Int = 400,
        random: Random = Random.Default,
    ): Double {
        require(numOpponents >= 1) { "Need at least one opponent" }
        require(holeCards.size == 2) { "Need exactly 2 hole cards" }
        require(community.size <= 5) { "community must have ≤ 5 cards" }

        val knownCards = (holeCards + community).toSet()
        val deck = Card.fullDeck.filter { it !in knownCards }

        var wins = 0.0
        repeat(iterations) {
            val shuffled = deck.toMutableList().apply { shuffle(random) }
            var cursor = 0

            val opponentHoles = List(numOpponents) {
                val a = shuffled[cursor++]
                val b = shuffled[cursor++]
                listOf(a, b)
            }
            val remainingBoardNeeded = 5 - community.size
            val board = community + List(remainingBoardNeeded) { shuffled[cursor++] }

            val myRank = HandEvaluator.evaluate(holeCards + board)
            val oppRanks = opponentHoles.map { HandEvaluator.evaluate(it + board) }
            val maxOpp = oppRanks.max()
            val cmp = myRank.compareTo(maxOpp)
            wins += when {
                cmp > 0 -> 1.0
                cmp == 0 -> 1.0 / (1 + oppRanks.count { it.compareTo(myRank) == 0 })
                else -> 0.0
            }
        }
        return wins / iterations
    }

    fun drawPotential(holeCards: List<Card>, community: List<Card>): DrawProfile {
        if (community.size !in 3..4) return DrawProfile(flushDraw = false, openEndedStraight = false, gutshot = false)
        val all = holeCards + community
        val flushDraw = Suit.all.any { suit -> all.count { it.suit == suit } == 4 }

        val ranks = all.map { it.rank.value }.toSet().sorted()
        val (openEnded, gutshot) = detectStraightDraws(ranks)
        return DrawProfile(flushDraw = flushDraw, openEndedStraight = openEnded, gutshot = gutshot)
    }

    private fun detectStraightDraws(uniqueRanks: List<Int>): Pair<Boolean, Boolean> {
        val ranksWithLowAce = if (14 in uniqueRanks) uniqueRanks + 1 else uniqueRanks
        val set = ranksWithLowAce.toSet()
        var openEnded = false
        var gutshot = false
        for (low in 1..10) {
            val needed = (low..low + 4).toList()
            val present = needed.count { it in set }
            if (present == 4) {
                val missing = needed.first { it !in set }
                if (missing == needed.first() || missing == needed.last()) {
                    openEnded = true
                } else {
                    gutshot = true
                }
            }
        }
        return openEnded to gutshot
    }

    private fun chenBase(rank: Rank): Double = when (rank) {
        Rank.Ace -> 10.0
        Rank.King -> 8.0
        Rank.Queen -> 7.0
        Rank.Jack -> 6.0
        else -> rank.value / 2.0
    }
}

@Serializable
data class DrawProfile(
    val flushDraw: Boolean,
    val openEndedStraight: Boolean,
    val gutshot: Boolean,
) {
    val hasDraw: Boolean get() = flushDraw || openEndedStraight || gutshot
}

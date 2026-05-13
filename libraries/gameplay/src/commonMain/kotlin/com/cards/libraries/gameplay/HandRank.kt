package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.Serializable

@Serializable
enum class HandCategory(val displayName: String) {
    HighCard("High Card"),
    Pair("Pair"),
    TwoPair("Two Pair"),
    ThreeOfAKind("Three of a Kind"),
    Straight("Straight"),
    Flush("Flush"),
    FullHouse("Full House"),
    FourOfAKind("Four of a Kind"),
    StraightFlush("Straight Flush"),
    RoyalFlush("Royal Flush"),
}

@Serializable
data class HandRank(
    val category: HandCategory,
    val tiebreakers: List<Int>,
    val bestFive: List<Card>,
) : Comparable<HandRank> {

    override fun compareTo(other: HandRank): Int {
        val byCategory = category.ordinal.compareTo(other.category.ordinal)
        if (byCategory != 0) return byCategory
        val tCount = maxOf(tiebreakers.size, other.tiebreakers.size)
        for (i in 0 until tCount) {
            val a = tiebreakers.getOrElse(i) { 0 }
            val b = other.tiebreakers.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}

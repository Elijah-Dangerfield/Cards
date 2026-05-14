package com.dangerfield.cards.libraries.gameplay

/**
 * Human-readable description of an evaluated hand — used in showdown UIs so
 * "Pair vs Pair" no longer looks like an arbitrary loss. Spells out which
 * rank the player has and, where the kicker decides the winner, names the
 * kicker too.
 *
 * Examples:
 *   "Pair of Kings, Queen kicker"
 *   "Two Pair, Aces and Eights"
 *   "Flush, Ace high"
 *   "Straight to the Ten"
 *   "Full House, Queens over Fives"
 *   "Royal Flush"
 */
fun HandRank.describe(): String {
    val ranks = tiebreakers.map { Rank.fromValue(it) }
    return when (category) {
        HandCategory.RoyalFlush -> "Royal Flush"
        HandCategory.StraightFlush -> "Straight Flush to the ${ranks.firstOrNull()?.fullName() ?: "—"}"
        HandCategory.FourOfAKind -> {
            val quad = ranks.getOrNull(0)?.fullName(plural = true) ?: "—"
            val kicker = ranks.getOrNull(1)?.fullName() ?: "—"
            "Four $quad, $kicker kicker"
        }
        HandCategory.FullHouse -> {
            val trips = ranks.getOrNull(0)?.fullName(plural = true) ?: "—"
            val pair = ranks.getOrNull(1)?.fullName(plural = true) ?: "—"
            "Full House, $trips over $pair"
        }
        HandCategory.Flush -> "Flush, ${ranks.firstOrNull()?.fullName() ?: "—"} high"
        HandCategory.Straight -> "Straight to the ${ranks.firstOrNull()?.fullName() ?: "—"}"
        HandCategory.ThreeOfAKind -> {
            val trips = ranks.getOrNull(0)?.fullName(plural = true) ?: "—"
            val kicker = ranks.getOrNull(1)?.fullName() ?: "—"
            "Three $trips, $kicker kicker"
        }
        HandCategory.TwoPair -> {
            val high = ranks.getOrNull(0)?.fullName(plural = true) ?: "—"
            val low = ranks.getOrNull(1)?.fullName(plural = true) ?: "—"
            "Two Pair, $high and $low"
        }
        HandCategory.Pair -> {
            val pair = ranks.getOrNull(0)?.fullName(plural = true) ?: "—"
            val kicker = ranks.getOrNull(1)?.fullName() ?: "—"
            "Pair of $pair, $kicker kicker"
        }
        HandCategory.HighCard -> "${ranks.firstOrNull()?.fullName() ?: "—"} high"
    }
}

private fun Rank.fullName(plural: Boolean = false): String = when (this) {
    Rank.Two -> if (plural) "Twos" else "Two"
    Rank.Three -> if (plural) "Threes" else "Three"
    Rank.Four -> if (plural) "Fours" else "Four"
    Rank.Five -> if (plural) "Fives" else "Five"
    Rank.Six -> if (plural) "Sixes" else "Six"
    Rank.Seven -> if (plural) "Sevens" else "Seven"
    Rank.Eight -> if (plural) "Eights" else "Eight"
    Rank.Nine -> if (plural) "Nines" else "Nine"
    Rank.Ten -> if (plural) "Tens" else "Ten"
    Rank.Jack -> if (plural) "Jacks" else "Jack"
    Rank.Queen -> if (plural) "Queens" else "Queen"
    Rank.King -> if (plural) "Kings" else "King"
    Rank.Ace -> if (plural) "Aces" else "Ace"
}

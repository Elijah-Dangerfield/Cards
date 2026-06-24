package com.dangerfield.cards.libraries.gameplay

object HandEvaluator {

    fun evaluate(cards: List<Card>): HandRank {
        require(cards.size in 5..7) {
            "Hand evaluation requires 5 to 7 cards (got ${cards.size})"
        }
        require(cards.distinct().size == cards.size) {
            "Duplicate cards in hand: $cards"
        }
        return evaluateValidated(cards)
    }

    /**
     * Like [evaluate], but returns null instead of throwing when handed a
     * malformed card set (wrong count or a duplicate card). On-device callers
     * assemble the eval set from `holeCards + community`, and a [GameState]
     * snapshot that lands over stale local cards can briefly produce an
     * overlapping set. The engine itself constructs cards from a single deck
     * and must never hit this, so server-authoritative paths keep calling
     * [evaluate] so the strict contract still surfaces real engine bugs there.
     * Display + progression projections call this so a transient desync degrades
     * to "no hand shown" rather than crashing the play screen (MP-4).
     */
    fun evaluateOrNull(cards: List<Card>): HandRank? {
        if (cards.size !in 5..7) return null
        if (cards.distinct().size != cards.size) return null
        return evaluateValidated(cards)
    }

    private fun evaluateValidated(cards: List<Card>): HandRank {
        if (cards.size == 5) return evaluateFive(cards)

        var best: HandRank? = null
        forEachCombinationOfFive(cards) { five ->
            val rank = evaluateFive(five)
            if (best == null || rank > best!!) best = rank
        }
        return best!!
    }

    private fun evaluateFive(five: List<Card>): HandRank {
        require(five.size == 5)

        val sortedDesc = five.sortedByDescending { it.rank.value }
        val rankCounts: Map<Rank, Int> = sortedDesc
            .groupingBy { it.rank }
            .eachCount()

        val isFlush = five.map { it.suit }.toSet().size == 1
        val straightHigh = straightHighRank(sortedDesc.map { it.rank.value })

        if (isFlush && straightHigh != null) {
            val cards = canonicalStraightCards(five, straightHigh)
            return if (straightHigh == Rank.Ace.value) {
                HandRank(HandCategory.RoyalFlush, listOf(Rank.Ace.value), cards)
            } else {
                HandRank(HandCategory.StraightFlush, listOf(straightHigh), cards)
            }
        }

        val groups: List<Pair<Rank, Int>> = rankCounts.entries
            .map { it.key to it.value }
            .sortedWith(compareByDescending<Pair<Rank, Int>> { it.second }.thenByDescending { it.first.value })

        val countSignature = groups.map { it.second }

        if (countSignature == listOf(4, 1)) {
            val quadRank = groups[0].first
            val kicker = groups[1].first
            return HandRank(
                category = HandCategory.FourOfAKind,
                tiebreakers = listOf(quadRank.value, kicker.value),
                bestFive = sortedDesc.filter { it.rank == quadRank } +
                    sortedDesc.filter { it.rank == kicker },
            )
        }

        if (countSignature == listOf(3, 2)) {
            val tripsRank = groups[0].first
            val pairRank = groups[1].first
            return HandRank(
                category = HandCategory.FullHouse,
                tiebreakers = listOf(tripsRank.value, pairRank.value),
                bestFive = sortedDesc.filter { it.rank == tripsRank } +
                    sortedDesc.filter { it.rank == pairRank },
            )
        }

        if (isFlush) {
            return HandRank(
                category = HandCategory.Flush,
                tiebreakers = sortedDesc.map { it.rank.value },
                bestFive = sortedDesc,
            )
        }

        if (straightHigh != null) {
            return HandRank(
                category = HandCategory.Straight,
                tiebreakers = listOf(straightHigh),
                bestFive = canonicalStraightCards(five, straightHigh),
            )
        }

        if (countSignature == listOf(3, 1, 1)) {
            val tripsRank = groups[0].first
            val kickers = groups.drop(1).map { it.first }.sortedByDescending { it.value }
            return HandRank(
                category = HandCategory.ThreeOfAKind,
                tiebreakers = listOf(tripsRank.value) + kickers.map { it.value },
                bestFive = sortedDesc.filter { it.rank == tripsRank } +
                    kickers.flatMap { k -> sortedDesc.filter { it.rank == k } },
            )
        }

        if (countSignature == listOf(2, 2, 1)) {
            val pairs = groups.filter { it.second == 2 }.map { it.first }.sortedByDescending { it.value }
            val kicker = groups.first { it.second == 1 }.first
            return HandRank(
                category = HandCategory.TwoPair,
                tiebreakers = listOf(pairs[0].value, pairs[1].value, kicker.value),
                bestFive = pairs.flatMap { p -> sortedDesc.filter { it.rank == p } } +
                    sortedDesc.filter { it.rank == kicker },
            )
        }

        if (countSignature == listOf(2, 1, 1, 1)) {
            val pairRank = groups[0].first
            val kickers = groups.drop(1).map { it.first }.sortedByDescending { it.value }
            return HandRank(
                category = HandCategory.Pair,
                tiebreakers = listOf(pairRank.value) + kickers.map { it.value },
                bestFive = sortedDesc.filter { it.rank == pairRank } +
                    kickers.flatMap { k -> sortedDesc.filter { it.rank == k } },
            )
        }

        return HandRank(
            category = HandCategory.HighCard,
            tiebreakers = sortedDesc.map { it.rank.value },
            bestFive = sortedDesc,
        )
    }

    private fun straightHighRank(rankValuesDesc: List<Int>): Int? {
        val unique = rankValuesDesc.toSet()
        if (unique.size != 5) return null
        val sorted = unique.sortedDescending()
        if (sorted == listOf(14, 5, 4, 3, 2)) return 5
        val high = sorted.first()
        val low = sorted.last()
        if (high - low == 4) return high
        return null
    }

    private fun canonicalStraightCards(cards: List<Card>, highRank: Int): List<Card> {
        if (highRank == 5) {
            val byRank = cards.associateBy { it.rank.value }
            val five = byRank[5] ?: error("missing 5 in wheel")
            val four = byRank[4] ?: error("missing 4 in wheel")
            val three = byRank[3] ?: error("missing 3 in wheel")
            val two = byRank[2] ?: error("missing 2 in wheel")
            val ace = byRank[14] ?: error("missing A in wheel")
            return listOf(five, four, three, two, ace)
        }
        return cards.sortedByDescending { it.rank.value }
    }

    private inline fun forEachCombinationOfFive(
        cards: List<Card>,
        action: (List<Card>) -> Unit,
    ) {
        val n = cards.size
        for (a in 0 until n - 4) {
            for (b in a + 1 until n - 3) {
                for (c in b + 1 until n - 2) {
                    for (d in c + 1 until n - 1) {
                        for (e in d + 1 until n) {
                            action(listOf(cards[a], cards[b], cards[c], cards[d], cards[e]))
                        }
                    }
                }
            }
        }
    }
}

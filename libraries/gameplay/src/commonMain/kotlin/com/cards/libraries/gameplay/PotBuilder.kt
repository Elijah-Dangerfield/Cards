package com.dangerfield.cards.libraries.gameplay

object PotBuilder {

    fun buildPots(seats: List<Seat>): List<Pot> {
        val contributors = seats.filter { it.contributedThisHand > 0 }
        if (contributors.isEmpty()) return emptyList()

        val levels: List<Long> = contributors
            .map { it.contributedThisHand }
            .distinct()
            .sorted()

        var prev = 0L
        val pots = mutableListOf<Pot>()
        for (level in levels) {
            val tierSize = level - prev
            if (tierSize <= 0) continue
            var amount = 0L
            val eligible = mutableListOf<Int>()
            for (seat in seats) {
                val contribAtTier = (minOf(seat.contributedThisHand, level) -
                    minOf(seat.contributedThisHand, prev)).coerceAtLeast(0)
                amount += contribAtTier
                if (seat.isInHand && seat.contributedThisHand >= level) {
                    eligible += seat.index
                }
            }
            if (amount > 0 && eligible.isNotEmpty()) {
                pots += Pot(amount = amount, eligibleSeatIndexes = eligible.sorted())
            } else if (amount > 0 && eligible.isEmpty()) {
                val mergedInto = pots.lastOrNull()
                if (mergedInto != null) {
                    pots[pots.lastIndex] = mergedInto.copy(amount = mergedInto.amount + amount)
                } else {
                    pots += Pot(amount = amount, eligibleSeatIndexes = emptyList())
                }
            }
            prev = level
        }

        return mergeRedundantPots(pots)
    }

    private fun mergeRedundantPots(pots: List<Pot>): List<Pot> {
        if (pots.size < 2) return pots
        val out = mutableListOf<Pot>()
        for (p in pots) {
            val last = out.lastOrNull()
            if (last != null && last.eligibleSeatIndexes == p.eligibleSeatIndexes) {
                out[out.lastIndex] = last.copy(amount = last.amount + p.amount)
            } else {
                out += p
            }
        }
        return out
    }
}

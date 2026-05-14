package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.HandCategoryGrade
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource

/**
 * Pure function from a finished hand to XP awards.
 *
 * Formula (multiplayer baseline, halved for bots per docs/decisions.md 2026-05-14):
 *   BASE          = 10  (any finished hand — even an instant fold)
 *   INVESTMENT    = clamp(chipsCommitted / bigBlind, 0..20) × 1
 *   SHOWDOWN      = 10  (only if reachedShowdown)
 *   HAND_STRENGTH = curve(handCategory) (only at showdown; see [HAND_STRENGTH_BY_GRADE])
 *
 * Bots get half of each award (integer division, rounded down).
 *
 * Invariant: the awards depend ONLY on engagement signals. The summary's
 * `wonPot` field is intentionally not read here — XP must not move based on
 * who won the pot.
 *
 * **Curve choice:** hand-strength bonuses grow super-linearly so showing a
 * flush feels significantly more rewarding than showing two pair. Skilled
 * play (reaching showdown with strong holdings) leans heavily on this term;
 * fold-spam players still get base XP but the per-hand ceiling stays modest.
 */
internal data class XpAward(val source: XpSource, val amount: Int)

internal object XpCalculator {

    fun calculate(summary: HandResultSummary): List<XpAward> {
        val raw = buildList {
            add(XpAward(XpSource.BASE, BASE_XP))

            if (summary.bigBlind > 0) {
                val bbCommitted = (summary.chipsCommitted / summary.bigBlind)
                    .coerceIn(0, INVESTMENT_BB_CAP.toLong())
                    .toInt()
                if (bbCommitted > 0) {
                    add(XpAward(XpSource.INVESTMENT, bbCommitted * INVESTMENT_XP_PER_BB))
                }
            }

            if (summary.reachedShowdown) {
                add(XpAward(XpSource.SHOWDOWN, SHOWDOWN_XP))
                val category = summary.handCategory
                if (category != null) {
                    add(XpAward(XpSource.HAND_STRENGTH, HAND_STRENGTH_BY_GRADE[category.ordinal]))
                }
            }
        }

        val multiplier = when (summary.mode) {
            XpMode.MULTIPLAYER -> 1.0
            XpMode.BOTS -> BOT_MULTIPLIER
        }

        return raw
            .map { it.copy(amount = (it.amount * multiplier).toInt()) }
            .filter { it.amount > 0 }
    }

    private const val BASE_XP = 10
    private const val SHOWDOWN_XP = 10
    private const val INVESTMENT_XP_PER_BB = 1
    private const val INVESTMENT_BB_CAP = 20
    private const val BOT_MULTIPLIER = 0.5

    /**
     * Hand-strength bonus by [HandCategoryGrade] ordinal (HighCard..RoyalFlush).
     * Super-linear so the gap between, say, Two Pair and a Flush feels real.
     */
    private val HAND_STRENGTH_BY_GRADE: IntArray = intArrayOf(
        2,   // HighCard
        4,   // Pair
        8,   // TwoPair
        14,  // ThreeOfAKind
        22,  // Straight
        32,  // Flush
        44,  // FullHouse
        60,  // FourOfAKind
        80,  // StraightFlush
        100, // RoyalFlush
    )
}

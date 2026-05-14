package com.dangerfield.cards.libraries.cards.impl

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
 *   HAND_STRENGTH = (categoryGrade.ordinal + 1) × 2   (1..20, only at showdown)
 *
 * Bots get half of each award (integer division, rounded down).
 *
 * Invariant: the awards depend ONLY on engagement signals. The summary's
 * `wonPot` field is intentionally not read here — XP must not move based on
 * who won the pot.
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
                    val strength = (category.ordinal + 1) * HAND_STRENGTH_XP_PER_GRADE
                    add(XpAward(XpSource.HAND_STRENGTH, strength))
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

    // Tuned for "feels good after one session" — flat per hand around 10-15 XP
    // vs. bots, more like 25-30 XP for a played-to-showdown hand. Easy to retune
    // once we have real session data.
    private const val BASE_XP = 10
    private const val SHOWDOWN_XP = 10
    private const val INVESTMENT_XP_PER_BB = 1
    private const val INVESTMENT_BB_CAP = 20
    private const val HAND_STRENGTH_XP_PER_GRADE = 2
    private const val BOT_MULTIPLIER = 0.5
}

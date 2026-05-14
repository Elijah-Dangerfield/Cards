package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.HandCategoryGrade
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XpCalculatorTest {

    private fun summary(
        wasFold: Boolean = false,
        reachedShowdown: Boolean = false,
        wonPot: Boolean = false,
        chipsCommitted: Long = 0,
        bigBlind: Long = 10,
        handCategory: HandCategoryGrade? = null,
        mode: XpMode = XpMode.BOTS,
    ) = HandResultSummary(
        handId = "1",
        mode = mode,
        wasFold = wasFold,
        reachedShowdown = reachedShowdown,
        wonPot = wonPot,
        chipsCommitted = chipsCommitted,
        bigBlind = bigBlind,
        handCategory = handCategory,
    )

    @Test
    fun foldPreflopAwardsOnlyBase() {
        val awards = XpCalculator.calculate(summary(wasFold = true))
        assertEquals(1, awards.size)
        assertEquals(XpSource.BASE, awards[0].source)
        // BASE 10 × 0.5 (bots) = 5
        assertEquals(5, awards[0].amount)
    }

    @Test
    fun investmentScalesWithChipsCommitted() {
        val awards = XpCalculator.calculate(
            summary(wasFold = true, chipsCommitted = 80, bigBlind = 10),
        )
        val investment = awards.first { it.source == XpSource.INVESTMENT }
        // 80 / 10 = 8 BB; 8 × 1 = 8 raw; × 0.5 bots = 4
        assertEquals(4, investment.amount)
    }

    @Test
    fun investmentIsCappedAt20BigBlinds() {
        val awards = XpCalculator.calculate(
            summary(wasFold = true, chipsCommitted = 10_000, bigBlind = 10),
        )
        val investment = awards.first { it.source == XpSource.INVESTMENT }
        // cap = 20 BB × 1 = 20 raw; × 0.5 = 10
        assertEquals(10, investment.amount)
    }

    @Test
    fun reachingShowdownAddsShowdownAndStrengthBonuses() {
        val awards = XpCalculator.calculate(
            summary(
                reachedShowdown = true,
                wonPot = false,
                chipsCommitted = 20,
                bigBlind = 10,
                handCategory = HandCategoryGrade.TwoPair,
            ),
        )
        assertTrue(awards.any { it.source == XpSource.SHOWDOWN })
        assertTrue(awards.any { it.source == XpSource.HAND_STRENGTH })
        // TwoPair raw bonus is 8 (super-linear curve) → × 0.5 bots = 4.
        val strength = awards.first { it.source == XpSource.HAND_STRENGTH }
        assertEquals(4, strength.amount)
    }

    @Test
    fun handStrengthBonusGrowsSuperLinearly() {
        // The whole point of the curve is that big hands feel meaningfully
        // bigger than small ones. Asserts the gap between Two Pair and Flush
        // is at least 4× the gap between High Card and Pair.
        fun strengthFor(grade: HandCategoryGrade): Int = XpCalculator
            .calculate(summary(reachedShowdown = true, handCategory = grade))
            .first { it.source == XpSource.HAND_STRENGTH }
            .amount

        val highToPair = strengthFor(HandCategoryGrade.Pair) - strengthFor(HandCategoryGrade.HighCard)
        val twoPairToFlush = strengthFor(HandCategoryGrade.Flush) - strengthFor(HandCategoryGrade.TwoPair)
        assertTrue(
            twoPairToFlush > highToPair * 4,
            "Expected the Flush-vs-TwoPair gap ($twoPairToFlush) to dwarf the Pair-vs-HighCard gap ($highToPair)",
        )
    }

    @Test
    fun multiplayerEarnsTwiceTheBotRate() {
        val botTotal = XpCalculator.calculate(
            summary(
                reachedShowdown = true,
                chipsCommitted = 60,
                bigBlind = 10,
                handCategory = HandCategoryGrade.Flush,
                mode = XpMode.BOTS,
            ),
        ).sumOf { it.amount }
        val multiplayerTotal = XpCalculator.calculate(
            summary(
                reachedShowdown = true,
                chipsCommitted = 60,
                bigBlind = 10,
                handCategory = HandCategoryGrade.Flush,
                mode = XpMode.MULTIPLAYER,
            ),
        ).sumOf { it.amount }
        // Each award doubles; total in MP should be exactly twice the bot total.
        assertEquals(botTotal * 2, multiplayerTotal)
    }

    @Test
    fun winningOrLosingTheSameShowdownEarnsSameXp() {
        // Decoupling invariant per docs/decisions.md (2026-05-14): XP must not
        // depend on whether the player won the pot.
        val baseArgs = summary(
            reachedShowdown = true,
            chipsCommitted = 50,
            bigBlind = 10,
            handCategory = HandCategoryGrade.Straight,
        )
        val winningHand = XpCalculator.calculate(baseArgs.copy(wonPot = true))
        val losingHand = XpCalculator.calculate(baseArgs.copy(wonPot = false))
        assertEquals(
            winningHand.sumOf { it.amount },
            losingHand.sumOf { it.amount },
        )
    }

    @Test
    fun zeroBigBlindNeverProducesInvestmentXp() {
        // Guards a divide-by-zero in the formula. Engine validates bigBlind > 0
        // but the calculator must not assume it.
        val awards = XpCalculator.calculate(
            summary(chipsCommitted = 100, bigBlind = 0, wasFold = true),
        )
        assertTrue(awards.none { it.source == XpSource.INVESTMENT })
    }

    @Test
    fun handStrengthOnlyAwardedAtShowdown() {
        // Even with handCategory provided, if reachedShowdown is false (fold),
        // there should be no strength bonus.
        val awards = XpCalculator.calculate(
            summary(
                wasFold = true,
                reachedShowdown = false,
                handCategory = HandCategoryGrade.FourOfAKind,
            ),
        )
        assertTrue(awards.none { it.source == XpSource.HAND_STRENGTH })
    }
}

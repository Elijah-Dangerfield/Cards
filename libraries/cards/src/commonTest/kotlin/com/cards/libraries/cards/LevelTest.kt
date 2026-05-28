package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelTest {

    @Test
    fun xpToLevelUpFrom_returnsQuadraticCurve() {
        assertEquals(100L, xpToLevelUpFrom(1))
        assertEquals(400L, xpToLevelUpFrom(2))
        assertEquals(900L, xpToLevelUpFrom(3))
        assertEquals(2_500L, xpToLevelUpFrom(5))
        assertEquals(10_000L, xpToLevelUpFrom(10))
        assertEquals(MAX_LEVEL.toLong() * MAX_LEVEL.toLong() * 100L, xpToLevelUpFrom(MAX_LEVEL))
    }

    @Test
    fun xpToLevelUpFrom_belowOneIsClampedToLevelOne() {
        val expected = xpToLevelUpFrom(1)
        assertEquals(expected, xpToLevelUpFrom(0))
        assertEquals(expected, xpToLevelUpFrom(-5))
    }

    @Test
    fun levelProgressFor_zeroXp_isLevelOne() {
        val progress = levelProgressFor(0)
        assertEquals(LevelProgress(level = 1, totalXp = 0, xpAtLevelStart = 0, xpForNextLevel = 100), progress)
    }

    @Test
    fun levelProgressFor_justBeforeLevelTwo_isLevelOne() {
        val progress = levelProgressFor(99)
        assertEquals(1, progress.level)
        assertEquals(0L, progress.xpAtLevelStart)
        assertEquals(100L, progress.xpForNextLevel)
    }

    @Test
    fun levelProgressFor_exactlyOnLevelTwoThreshold_advances() {
        val progress = levelProgressFor(100)
        assertEquals(2, progress.level)
        assertEquals(100L, progress.xpAtLevelStart)
        assertEquals(400L, progress.xpForNextLevel)
    }

    @Test
    fun levelProgressFor_negativeXp_clampsToZeroLevelOne() {
        val progress = levelProgressFor(-1L)
        assertEquals(LevelProgress(level = 1, totalXp = 0, xpAtLevelStart = 0, xpForNextLevel = 100), progress)
    }

    @Test
    fun levelProgressFor_xpBeyondMaxLevel_returnsMaxLevel() {
        val maxLevelCumulative = (1 until MAX_LEVEL).sumOf { xpToLevelUpFrom(it) }
        val absurdXp = maxLevelCumulative + 5_000_000L

        val progress = levelProgressFor(absurdXp)

        assertEquals(MAX_LEVEL, progress.level)
        assertEquals(maxLevelCumulative, progress.xpAtLevelStart)
        assertEquals(xpToLevelUpFrom(MAX_LEVEL), progress.xpForNextLevel)
        assertEquals(absurdXp, progress.totalXp)
    }

    @Test
    fun levelProgress_fraction_isXpIntoLevelOverNeeded() {
        val progress = LevelProgress(level = 1, totalXp = 50, xpAtLevelStart = 0, xpForNextLevel = 100)
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun levelProgress_fraction_returnsZeroWhenNoXpForNextLevel() {
        val progress = LevelProgress(level = 1, totalXp = 50, xpAtLevelStart = 0, xpForNextLevel = 0)
        assertEquals(0f, progress.fraction)
    }

    @Test
    fun levelProgress_fraction_clampsAtOneWhenOverfilled() {
        val progress = LevelProgress(level = 1, totalXp = 200, xpAtLevelStart = 0, xpForNextLevel = 100)
        assertEquals(1f, progress.fraction)
    }

    @Test
    fun levelProgress_xpIntoLevel_subtractsLevelStart() {
        val progress = LevelProgress(level = 3, totalXp = 700, xpAtLevelStart = 500, xpForNextLevel = 900)
        assertEquals(200L, progress.xpIntoLevel)
    }

    @Test
    fun levelProgress_xpToNextLevel_isRemainingNeeded() {
        val progress = LevelProgress(level = 3, totalXp = 700, xpAtLevelStart = 500, xpForNextLevel = 900)
        assertEquals(700L, progress.xpToNextLevel)
    }

    @Test
    fun levelProgressFor_alignsAcrossSeveralLevels() {
        val totalsByLevel = (1..6).map { lvl ->
            val cumulative = (1 until lvl).sumOf { xpToLevelUpFrom(it) }
            lvl to cumulative
        }

        totalsByLevel.forEach { (lvl, cumulative) ->
            val progress = levelProgressFor(cumulative)
            assertEquals(lvl, progress.level, "level for $cumulative XP")
            assertEquals(cumulative, progress.xpAtLevelStart)
            assertEquals(xpToLevelUpFrom(lvl), progress.xpForNextLevel)
        }
    }

    @Test
    fun levelProgressFor_isMonotonicallyNonDecreasingInLevel() {
        var lastLevel = 0
        var totalXp = 0L
        while (totalXp < 1_000_000L) {
            val level = levelProgressFor(totalXp).level
            assertTrue(level >= lastLevel, "level dropped at $totalXp XP: $lastLevel -> $level")
            lastLevel = level
            totalXp += 137L
        }
    }
}

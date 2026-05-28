package com.dangerfield.cards.features.progression.impl

import com.dangerfield.cards.libraries.ui.components.achievement.EarnedAgo
import com.dangerfield.cards.libraries.ui.components.achievement.earnedAgo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `earnedAgo(epochMs, nowEpochMs)` factory's bucketing across
 * each [EarnedAgo] variant. Replaces the prior `formatEarnedAgo` string
 * assertions — same boundaries, structural shape instead of literal text
 * so the strings live in `:libraries:resources` and the test doesn't have
 * to track copy changes.
 */
class FormatEarnedAgoTest {

    private val day: Long = 24L * 60L * 60L * 1000L
    private val now: Long = 1_700_000_000_000L

    @Test
    fun zeroDaysAgo_isToday() {
        assertEquals(EarnedAgo.Today, earnedAgo(now, now))
        assertEquals(EarnedAgo.Today, earnedAgo(now - day + 1, now))
    }

    @Test
    fun oneDayAgo_isYesterday() {
        assertEquals(EarnedAgo.Yesterday, earnedAgo(now - day, now))
    }

    @Test
    fun underAWeek_isDaysAgo() {
        assertEquals(EarnedAgo.DaysAgo(3), earnedAgo(now - 3 * day, now))
        assertEquals(EarnedAgo.DaysAgo(6), earnedAgo(now - 6 * day, now))
    }

    @Test
    fun underAMonth_isWeeksAgo() {
        assertEquals(EarnedAgo.WeeksAgo(1), earnedAgo(now - 7 * day, now))
        assertEquals(EarnedAgo.WeeksAgo(4), earnedAgo(now - 29 * day, now))
    }

    @Test
    fun underAYear_isMonthsAgo() {
        assertEquals(EarnedAgo.MonthsAgo(1), earnedAgo(now - 30 * day, now))
        assertEquals(EarnedAgo.MonthsAgo(12), earnedAgo(now - 364 * day, now))
    }

    @Test
    fun overAYear_isYearsAgo() {
        assertEquals(EarnedAgo.YearsAgo(1), earnedAgo(now - 365 * day, now))
        assertEquals(EarnedAgo.YearsAgo(2), earnedAgo(now - 730 * day, now))
    }

    @Test
    fun futureEarnedAt_clampsToToday() {
        assertEquals(EarnedAgo.Today, earnedAgo(now + day, now))
    }
}

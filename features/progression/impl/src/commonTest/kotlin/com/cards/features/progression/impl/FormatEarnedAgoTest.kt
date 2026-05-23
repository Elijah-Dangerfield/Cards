package com.dangerfield.cards.features.progression.impl

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatEarnedAgoTest {

    private val day: Long = 24L * 60L * 60L * 1000L
    private val now: Long = 1_700_000_000_000L

    @Test
    fun zeroDaysAgo_isToday() {
        assertEquals("today", formatEarnedAgo(now, now))
        assertEquals("today", formatEarnedAgo(now - day + 1, now))
    }

    @Test
    fun oneDayAgo_isYesterday() {
        assertEquals("yesterday", formatEarnedAgo(now - day, now))
    }

    @Test
    fun underAWeek_isDaysAgo() {
        assertEquals("3d ago", formatEarnedAgo(now - 3 * day, now))
        assertEquals("6d ago", formatEarnedAgo(now - 6 * day, now))
    }

    @Test
    fun underAMonth_isWeeksAgo() {
        assertEquals("1w ago", formatEarnedAgo(now - 7 * day, now))
        assertEquals("4w ago", formatEarnedAgo(now - 29 * day, now))
    }

    @Test
    fun underAYear_isMonthsAgo() {
        assertEquals("1mo ago", formatEarnedAgo(now - 30 * day, now))
        assertEquals("12mo ago", formatEarnedAgo(now - 364 * day, now))
    }

    @Test
    fun overAYear_isYearsAgo() {
        assertEquals("1y ago", formatEarnedAgo(now - 365 * day, now))
        assertEquals("2y ago", formatEarnedAgo(now - 730 * day, now))
    }

    @Test
    fun futureEarnedAt_clampsToToday() {
        assertEquals("today", formatEarnedAgo(now + day, now))
    }
}

package com.dangerfield.cards.features.progression.impl

import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AllAchievements
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pure helpers backing the stats screen: the 3-up achievement
 * highlights strip (recency ordering + locked back-fill) and the
 * percent formatting for win/fold rates.
 */
class StatsScreenHelpersTest {

    @Test
    fun highlights_noEarned_backfillsWithLocked() {
        val slots = achievementHighlights(AchievementProgress.Empty)
        assertEquals(3, slots.size)
        assertTrue(slots.all { (_, earnedAt) -> earnedAt == null })
    }

    @Test
    fun highlights_singleEarned_leadsAndKeepsThreeSlots() {
        // The back-fill exists so a lone earned medal never renders as a
        // full-row blowup — the strip must stay 3-up at any earned count.
        val earnedId = AllAchievements.first().id
        val progress = AchievementProgress(
            earned = mapOf(earnedId to 1_700_000_000_000L),
            counters = emptyMap(),
            customCounters = emptyMap(),
        )
        val slots = achievementHighlights(progress)
        assertEquals(3, slots.size)
        assertEquals(earnedId, slots[0].first.id)
        assertEquals(1_700_000_000_000L, slots[0].second)
        assertEquals(null, slots[1].second)
        assertEquals(null, slots[2].second)
    }

    @Test
    fun highlights_ordersEarnedByMostRecentFirst() {
        val (a, b, c) = AllAchievements.take(3)
        val progress = AchievementProgress(
            earned = mapOf(a.id to 100L, b.id to 300L, c.id to 200L),
            counters = emptyMap(),
            customCounters = emptyMap(),
        )
        val slots = achievementHighlights(progress)
        assertEquals(listOf(b.id, c.id, a.id), slots.map { it.first.id })
    }

    @Test
    fun highlights_moreEarnedThanSlots_capsAtSlotCount() {
        val earned = AllAchievements.take(5)
            .mapIndexed { index, achievement -> achievement.id to (index + 1) * 100L }
            .toMap()
        val progress = AchievementProgress(
            earned = earned,
            counters = emptyMap(),
            customCounters = emptyMap(),
        )
        val slots = achievementHighlights(progress)
        assertEquals(3, slots.size)
        assertTrue(slots.all { (_, earnedAt) -> earnedAt != null })
    }

    @Test
    fun percentOf_zeroDenominator_showsDash() {
        // A brand-new player must not read a misleading "0%".
        assertEquals("-", percentOf(0, 0))
        assertEquals("-", percentOf(5, 0))
    }

    @Test
    fun percentOf_roundsToWholePercent() {
        assertEquals("33%", percentOf(1, 3))
        assertEquals("67%", percentOf(2, 3))
        assertEquals("50%", percentOf(1, 2))
        assertEquals("100%", percentOf(7, 7))
        assertEquals("0%", percentOf(0, 10))
    }
}

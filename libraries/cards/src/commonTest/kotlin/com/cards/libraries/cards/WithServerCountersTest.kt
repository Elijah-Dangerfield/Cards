package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `withServerCounters` re-sources the progress-bar counters from the server's
 * authoritative projection so they survive reinstall — pinned here for each
 * criterion shape plus the keep-local cases (level, tutorial, MP).
 */
class WithServerCountersTest {

    private val base = AchievementProgress(
        earned = mapOf(AchievementId.FIRST_HAND to 123L),
        counters = emptyMap(),
        customCounters = mapOf(
            // local-only keys the server doesn't fold — must survive the overlay
            CURRENT_LEVEL to 7,
            TUTORIAL_COMPLETE to 1,
            HANDS_PLAYED_MP to 4,
            // a stale local fact counter the server should overwrite
            MAX_POT_SEEN to 50,
        ),
    )

    @Test
    fun customCriteria_readFromServer_overwritingStaleLocal() {
        val merged = base.withServerCounters(mapOf(MAX_POT_SEEN to 900L, NO_BUST_STREAK to 12L))
        assertEquals(900, merged.customCounters[MAX_POT_SEEN], "server overwrites the stale local value")
        assertEquals(12, merged.customCounters[NO_BUST_STREAK])
    }

    @Test
    fun perAchievementCriteria_mapToTheirServerCounter() {
        val merged = base.withServerCounters(
            mapOf("hands_played" to 250L, "hands_won" to 80L, "show_Flush" to 3L),
        )
        // HANDS_100 (HandsPlayed target 100) reads the single hands_played counter.
        assertEquals(250, merged.counters[AchievementId.HANDS_100])
        // SHOW_FLUSH (ShowAtLeast Flush) reads show_Flush.
        assertEquals(3, merged.counters[AchievementId.SHOW_FLUSH])
        // a weaker show category is its own server counter, independent.
        assertEquals(0, merged.counters[AchievementId.SHOW_ROYAL_FLUSH] ?: 0)
    }

    @Test
    fun botWhisperer_isDerivedFromTheBotWinFamily() {
        val merged = base.withServerCounters(
            mapOf(
                winsVsBotKey("Jane") to 12L,   // ≥10 → counts
                winsVsBotKey("Mike") to 10L,   // ≥10 → counts
                winsVsBotKey("Gina") to 3L,    // <10 → doesn't
            ),
        )
        assertEquals(2, merged.customCounters[BOT_WHISPERER_BOTS_BEATEN])
    }

    @Test
    fun localOnlyCounters_andEarned_survive() {
        val merged = base.withServerCounters(mapOf(NO_BUST_STREAK to 5L))
        assertEquals(7, merged.customCounters[CURRENT_LEVEL], "XP-derived level kept from local")
        assertEquals(1, merged.customCounters[TUTORIAL_COMPLETE], "tutorial kept from local")
        assertEquals(4, merged.customCounters[HANDS_PLAYED_MP], "MP counter kept from local")
        assertEquals(mapOf(AchievementId.FIRST_HAND to 123L), merged.earned, "earned set preserved")
    }
}

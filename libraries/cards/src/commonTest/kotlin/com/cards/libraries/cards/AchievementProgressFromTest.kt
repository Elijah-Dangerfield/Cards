package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `achievementProgressFrom` builds the display + unlock progress from the
 * effective counters (server snapshot + unsynced outbox) — pinned here for each
 * criterion shape plus the supplied (level) and derived (bot-whisperer) cases.
 */
class AchievementProgressFromTest {

    private val earned = mapOf(AchievementId.FIRST_HAND to 123L)

    @Test
    fun customCriteria_readFromCounters() {
        val p = achievementProgressFrom(
            counters = mapOf(MAX_POT_SEEN to 900L, NO_BUST_STREAK to 12L),
            earned = earned,
            level = 3,
        )
        assertEquals(900, p.customCounters[MAX_POT_SEEN])
        assertEquals(12, p.customCounters[NO_BUST_STREAK])
    }

    @Test
    fun perAchievementCriteria_mapToTheirCounter() {
        val p = achievementProgressFrom(
            counters = mapOf("hands_played" to 250L, "hands_won" to 80L, "show_Flush" to 3L),
            earned = earned,
            level = 1,
        )
        assertEquals(250, p.counters[AchievementId.HANDS_100], "HandsPlayed reads hands_played")
        assertEquals(3, p.counters[AchievementId.SHOW_FLUSH], "ShowAtLeast(Flush) reads show_Flush")
        assertEquals(0, p.counters[AchievementId.SHOW_ROYAL_FLUSH] ?: 0, "a stronger show is its own counter")
    }

    @Test
    fun level_isSupplied_notFolded() {
        val p = achievementProgressFrom(counters = emptyMap(), earned = earned, level = 9)
        assertEquals(9, p.customCounters[CURRENT_LEVEL])
    }

    @Test
    fun botWhisperer_isDerivedFromTheBotWinFamily() {
        val p = achievementProgressFrom(
            counters = mapOf(
                winsVsBotKey("Jane") to 12L, // ≥10 → counts
                winsVsBotKey("Mike") to 10L, // ≥10 → counts
                winsVsBotKey("Gina") to 3L,  // <10 → doesn't
            ),
            earned = earned,
            level = 1,
        )
        assertEquals(2, p.customCounters[BOT_WHISPERER_BOTS_BEATEN])
    }

    @Test
    fun earnedSet_passesThrough() {
        val p = achievementProgressFrom(counters = emptyMap(), earned = earned, level = 1)
        assertEquals(earned, p.earned)
    }

    @Test
    fun isMet_whenCounterReachesTarget() {
        // HANDS_10 has a HandsPlayed(10) criterion.
        val hands10 = AllAchievementsById.getValue(AchievementId.HANDS_10)
        val below = achievementProgressFrom(mapOf("hands_played" to 9L), earned, level = 1)
        val at = achievementProgressFrom(mapOf("hands_played" to 10L), earned, level = 1)
        assertEquals(false, hands10.isMet(below))
        assertEquals(true, hands10.isMet(at))
    }
}

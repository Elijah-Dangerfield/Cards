package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.cosmeticRewardFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the client-side mirror of the achievement → inventory grant map.
 * The server is authoritative (apps/server/.../ClientGrantableAchievements.kt);
 * this test exists so dropping or renaming an entry on either side gets
 * caught loudly instead of silently leaving the unlock callout empty.
 */
class EarnableCosmeticsTest {

    @Test
    fun pot5000_mapsToPotMagnetTitle() {
        val reward = cosmeticRewardFor(AchievementId.POT_5000)
        assertEquals("title_pot_magnet", reward?.productId)
        assertEquals("Pot Magnet title", reward?.label)
    }

    @Test
    fun comebackFrom5BB_mapsToShortStackHeroTitle() {
        val reward = cosmeticRewardFor(AchievementId.COMEBACK_FROM_5BB)
        assertEquals("title_short_stack_hero", reward?.productId)
        assertEquals("Short Stack Hero title", reward?.label)
    }

    @Test
    fun dontCallItComeback_mapsToComebackKidCardBack() {
        val reward = cosmeticRewardFor(AchievementId.DONT_CALL_IT_COMEBACK)
        assertEquals("cardback_comeback_kid", reward?.productId)
        assertEquals("Comeback Kid card back", reward?.label)
    }

    @Test
    fun botWhisperer_mapsToBotWhispererTitle() {
        val reward = cosmeticRewardFor(AchievementId.BOT_WHISPERER)
        assertEquals("title_bot_whisperer", reward?.productId)
        assertEquals("Bot Whisperer title", reward?.label)
    }

    @Test
    fun bustDealt5_mapsToEliminatorEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.BUST_DEALT_5)
        assertEquals("emotes_eliminator", reward?.productId)
        assertEquals("Eliminator emote pack", reward?.label)
    }

    @Test
    fun tripleUp_mapsToBallerEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.TRIPLE_UP)
        assertEquals("emotes_baller", reward?.productId)
        assertEquals("Baller emote pack", reward?.label)
    }

    @Test
    fun noBust100_mapsToIronStackEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.NO_BUST_100)
        assertEquals("emotes_iron_stack", reward?.productId)
        assertEquals("Iron Stack emote pack", reward?.label)
    }

    @Test
    fun winByFold10_mapsToConvincerEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.WIN_BY_FOLD_10)
        assertEquals("emotes_convincer", reward?.productId)
        assertEquals("Convincer emote pack", reward?.label)
    }

    @Test
    fun achievementsWithoutCosmeticRewards_returnNull() {
        // Spot-checks across the most common categories. The goal is to
        // pin "no reward" so accidentally extending the when-branch to
        // cover an unrelated id (e.g. via copy-paste) fails the test.
        assertNull(cosmeticRewardFor(AchievementId.FIRST_HAND))
        assertNull(cosmeticRewardFor(AchievementId.HANDS_100))
        assertNull(cosmeticRewardFor(AchievementId.SHOW_ROYAL_FLUSH))
        assertNull(cosmeticRewardFor(AchievementId.TUTORIAL_COMPLETE))
        assertNull(cosmeticRewardFor(AchievementId.REACH_LEVEL_25))
    }
}

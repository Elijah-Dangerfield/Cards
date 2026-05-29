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
    fun goodFold25_mapsToDisciplinedEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.GOOD_FOLD_25)
        assertEquals("emotes_disciplined", reward?.productId)
        assertEquals("Disciplined emote pack", reward?.label)
    }

    @Test
    fun hands1000_mapsToGrinderEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.HANDS_1000)
        assertEquals("emotes_grinder", reward?.productId)
        assertEquals("Grinder emote pack", reward?.label)
    }

    @Test
    fun doubleUp_mapsToDoublerEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.DOUBLE_UP)
        assertEquals("emotes_doubler", reward?.productId)
        assertEquals("Doubler emote pack", reward?.label)
    }

    @Test
    fun challenging10Wins_mapsToTacticianEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.CHALLENGING_10_WINS)
        assertEquals("emotes_tactician", reward?.productId)
        assertEquals("Tactician emote pack", reward?.label)
    }

    @Test
    fun reachLevel25_mapsToFeltVeteranTitle() {
        val reward = cosmeticRewardFor(AchievementId.REACH_LEVEL_25)
        assertEquals("title_felt_veteran", reward?.productId)
        assertEquals("Felt Veteran title", reward?.label)
    }

    @Test
    fun beatJane10_mapsToInspectorEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.BEAT_JANE_10)
        assertEquals("emotes_inspector", reward?.productId)
        assertEquals("Inspector emote pack", reward?.label)
    }

    @Test
    fun beatDavid10_mapsToShowstopperEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.BEAT_DAVID_10)
        assertEquals("emotes_showstopper", reward?.productId)
        assertEquals("Showstopper emote pack", reward?.label)
    }

    @Test
    fun beatGina10_mapsToOutsmarterEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.BEAT_GINA_10)
        assertEquals("emotes_outsmarter", reward?.productId)
        assertEquals("Outsmarter emote pack", reward?.label)
    }

    @Test
    fun beatSteve10_mapsToMarathonerEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.BEAT_STEVE_10)
        assertEquals("emotes_marathoner", reward?.productId)
        assertEquals("Marathoner emote pack", reward?.label)
    }

    @Test
    fun beatMike10_mapsToTamerEmotePack() {
        val reward = cosmeticRewardFor(AchievementId.BEAT_MIKE_10)
        assertEquals("emotes_tamer", reward?.productId)
        assertEquals("Tamer emote pack", reward?.label)
    }

    @Test
    fun showRoyalFlush_mapsToRoyaltyTitle() {
        val reward = cosmeticRewardFor(AchievementId.SHOW_ROYAL_FLUSH)
        assertEquals("title_royalty", reward?.productId)
        assertEquals("Royalty title", reward?.label)
    }

    @Test
    fun showStraightFlush_mapsToSuitedRunTitle() {
        val reward = cosmeticRewardFor(AchievementId.SHOW_STRAIGHT_FLUSH)
        assertEquals("title_suited_run", reward?.productId)
        assertEquals("Suited Run title", reward?.label)
    }

    @Test
    fun showFullHouse_mapsToFullBoatTitle() {
        val reward = cosmeticRewardFor(AchievementId.SHOW_FULL_HOUSE)
        assertEquals("title_full_boat", reward?.productId)
        assertEquals("Full Boat title", reward?.label)
    }

    @Test
    fun achievementsWithoutCosmeticRewards_returnNull() {
        // Spot-checks across the most common categories. The goal is to
        // pin "no reward" so accidentally extending the when-branch to
        // cover an unrelated id (e.g. via copy-paste) fails the test.
        assertNull(cosmeticRewardFor(AchievementId.FIRST_HAND))
        assertNull(cosmeticRewardFor(AchievementId.HANDS_100))
        assertNull(cosmeticRewardFor(AchievementId.SHOW_FOUR_OF_KIND))
        assertNull(cosmeticRewardFor(AchievementId.TUTORIAL_COMPLETE))
        assertNull(cosmeticRewardFor(AchievementId.REACH_LEVEL_5))
    }
}

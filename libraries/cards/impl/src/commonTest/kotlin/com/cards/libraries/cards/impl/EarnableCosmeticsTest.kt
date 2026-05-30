package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.CosmeticTier
import com.dangerfield.cards.libraries.cards.cosmeticRewardFor
import com.dangerfield.cards.libraries.cards.tierForProductId
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
    fun achievementsWithoutCosmeticRewards_returnNull() {
        // Spot-checks across the most common categories. The goal is to
        // pin "no reward" so accidentally extending the when-branch to
        // cover an unrelated id (e.g. via copy-paste) fails the test.
        assertNull(cosmeticRewardFor(AchievementId.FIRST_HAND))
        assertNull(cosmeticRewardFor(AchievementId.HANDS_100))
        assertNull(cosmeticRewardFor(AchievementId.SHOW_FLUSH))
        assertNull(cosmeticRewardFor(AchievementId.TUTORIAL_COMPLETE))
        assertNull(cosmeticRewardFor(AchievementId.REACH_LEVEL_5))
    }

    @Test
    fun everyMappedCosmetic_isTaggedEarnOnly_inV1() {
        // V1 catalog ships every earnable cosmetic as `unlock_only = TRUE`
        // server-side (V17 / V20 / V27-V34 migrations), so the matching
        // client-side tier must read EARN_ONLY across the board. Flipping
        // a row to `unlock_only = FALSE` (i.e. adding the earn-or-buy
        // axis) needs a matching tier flip here — pinning every id keeps
        // the two sides from drifting silently.
        val earnOnlyIds = listOf(
            AchievementId.POT_5000,
            AchievementId.COMEBACK_FROM_5BB,
            AchievementId.DONT_CALL_IT_COMEBACK,
            AchievementId.BOT_WHISPERER,
            AchievementId.BUST_DEALT_5,
            AchievementId.TRIPLE_UP,
            AchievementId.NO_BUST_100,
            AchievementId.WIN_BY_FOLD_10,
            AchievementId.GOOD_FOLD_25,
            AchievementId.HANDS_1000,
            AchievementId.DOUBLE_UP,
            AchievementId.CHALLENGING_10_WINS,
            AchievementId.REACH_LEVEL_25,
            AchievementId.BEAT_JANE_10,
            AchievementId.BEAT_DAVID_10,
            AchievementId.BEAT_GINA_10,
            AchievementId.BEAT_STEVE_10,
            AchievementId.BEAT_MIKE_10,
        )
        earnOnlyIds.forEach { id ->
            assertEquals(
                CosmeticTier.EARN_ONLY,
                cosmeticRewardFor(id)?.tier,
                "Expected $id to be tagged EARN_ONLY",
            )
        }
    }

    @Test
    fun tierForProductId_returnsTierForKnownProduct() {
        // Each productId is the inverse of the achievement → reward map;
        // spot-check the same shape on each of the 18 V1 entries.
        assertEquals(CosmeticTier.EARN_ONLY, tierForProductId("title_pot_magnet"))
        assertEquals(CosmeticTier.EARN_ONLY, tierForProductId("cardback_comeback_kid"))
        assertEquals(CosmeticTier.EARN_ONLY, tierForProductId("emotes_grinder"))
        assertEquals(CosmeticTier.EARN_ONLY, tierForProductId("title_felt_veteran"))
    }

    @Test
    fun tierForProductId_returnsNullForUnknownProduct() {
        // Shop-only products (chip packs, BUY_ONLY cosmetics) have no
        // achievement-grant mapping and should fall through to null so
        // the render layer reads them as "no provenance badge".
        assertNull(tierForProductId("chips_1000"))
        assertNull(tierForProductId("totally_made_up"))
        assertNull(tierForProductId(""))
    }

    @Test
    fun tierForProductId_mirrorsCosmeticRewardForEveryAchievement() {
        // For every (achievementId, reward) pair, looking up the
        // productId should return the exact same tier. Guards against
        // drift between the forward map (used by unlock callouts) and
        // the inverse lookup (used by inventory / shop badges).
        AchievementId.entries.forEach { id ->
            val reward = cosmeticRewardFor(id) ?: return@forEach
            assertEquals(
                reward.tier,
                tierForProductId(reward.productId),
                "tierForProductId(${reward.productId}) must match cosmeticRewardFor($id).tier",
            )
        }
    }

    @Test
    fun singleShowdownTitleEarnables_returnNull_afterRngRetire() {
        // The four "show X at showdown" achievements used to drop a title
        // cosmetic. Per the §A achievement-difficulty audit, single-
        // showdown RNG isn't a status signal worth pinning a cosmetic on,
        // so the title pairings were retired (XP/chip rewards stay on the
        // achievement itself, and `cosmeticRewardFor` falls through to
        // null). Pin the null so a future copy-paste doesn't reintroduce
        // a title for the same achievement id.
        assertNull(cosmeticRewardFor(AchievementId.SHOW_ROYAL_FLUSH))
        assertNull(cosmeticRewardFor(AchievementId.SHOW_STRAIGHT_FLUSH))
        assertNull(cosmeticRewardFor(AchievementId.SHOW_FULL_HOUSE))
        assertNull(cosmeticRewardFor(AchievementId.SHOW_FOUR_OF_KIND))
    }
}

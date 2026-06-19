package com.dangerfield.cards.libraries.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [LevelRewardsConfig] — the structured config shape the level-up grant +
 * reveal both read. The default table is load-bearing economy content, so this
 * guards the exact prizes against an accidental retune, and the entry → reward
 * mapping against a wiring regression.
 */
class LevelRewardsConfigTest {

    @Test
    fun defaultTable_matchesTheShippedEconomy() {
        assertEquals(listOf(LevelReward.Chips(1_000)), DefaultLevelRewards.rewardsForLevel(3))
        assertEquals(listOf(LevelReward.Chips(2_500)), DefaultLevelRewards.rewardsForLevel(5))
        assertEquals(listOf(LevelReward.Chips(4_000)), DefaultLevelRewards.rewardsForLevel(7))
        assertEquals(
            listOf(LevelReward.Chips(7_500), LevelReward.XpBoost(XP_BOOST_DEFAULT_DURATION_MS)),
            DefaultLevelRewards.rewardsForLevel(10),
        )
        assertEquals(listOf(LevelReward.Chips(12_500)), DefaultLevelRewards.rewardsForLevel(15))
        assertEquals(
            listOf(LevelReward.Chips(20_000), LevelReward.XpBoost(XP_BOOST_DEFAULT_DURATION_MS)),
            DefaultLevelRewards.rewardsForLevel(20),
        )
    }

    @Test
    fun unlistedLevel_grantsNothing() {
        assertTrue(DefaultLevelRewards.rewardsForLevel(1).isEmpty())
        assertTrue(DefaultLevelRewards.rewardsForLevel(4).isEmpty())
        assertTrue(DefaultLevelRewards.rewardsForLevel(99).isEmpty())
    }

    @Test
    fun entry_mapsOptionalFieldsIndependently() {
        assertEquals(
            listOf(LevelReward.Chips(500)),
            LevelRewardConfigEntry(level = 2, chips = 500).toRewards(),
        )
        assertEquals(
            listOf(LevelReward.XpBoost(1_000)),
            LevelRewardConfigEntry(level = 2, xpBoostMs = 1_000).toRewards(),
        )
        assertEquals(
            listOf(LevelReward.Cosmetic("cardback_high_roller")),
            LevelRewardConfigEntry(level = 2, cosmeticProductId = "cardback_high_roller").toRewards(),
        )
        assertEquals(
            listOf(
                LevelReward.Chips(500),
                LevelReward.XpBoost(1_000),
                LevelReward.Cosmetic("felt_emerald"),
            ),
            LevelRewardConfigEntry(
                level = 2,
                chips = 500,
                xpBoostMs = 1_000,
                cosmeticProductId = "felt_emerald",
            ).toRewards(),
        )
        assertTrue(LevelRewardConfigEntry(level = 2).toRewards().isEmpty())
    }
}

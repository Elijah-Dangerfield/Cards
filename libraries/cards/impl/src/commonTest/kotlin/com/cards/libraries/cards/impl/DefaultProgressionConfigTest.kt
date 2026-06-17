package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.config.AppConfigMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the config seam: [DefaultProgressionConfig] reads the level-rewards table
 * off the merged app-config tree, so a server override retunes the economy
 * without a release — and a missing value falls back to the bundled default.
 */
class DefaultProgressionConfigTest {

    private class MapAppConfig(override val map: Map<String, *>) : AppConfigMap()

    private fun configFrom(map: Map<String, *>): DefaultProgressionConfig =
        DefaultProgressionConfig(LevelRewardsConfigValue(MapAppConfig(map)))

    @Test
    fun missingPath_fallsBackToBundledDefault() {
        val config = configFrom(emptyMap<String, Any>())

        assertEquals(listOf(LevelReward.Chips(1_000)), config.rewardsForLevel(3))
        assertTrue(config.rewardsForLevel(2).isEmpty())
    }

    @Test
    fun serverOverride_retunesTheTable() {
        val config = configFrom(
            mapOf(
                "progression" to mapOf(
                    "levelRewards" to mapOf(
                        "levels" to listOf(
                            mapOf("level" to 2, "chips" to 500L),
                            mapOf("level" to 4, "chips" to 9_000L, "xpBoostMs" to 60_000L),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(LevelReward.Chips(500)), config.rewardsForLevel(2))
        assertEquals(
            listOf(LevelReward.Chips(9_000), LevelReward.XpBoost(60_000)),
            config.rewardsForLevel(4),
        )
        // A level the override dropped no longer grants the old default prize.
        assertTrue(config.rewardsForLevel(3).isEmpty())
    }
}

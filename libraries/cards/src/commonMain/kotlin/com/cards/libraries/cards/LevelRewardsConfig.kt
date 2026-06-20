package com.dangerfield.cards.libraries.cards

import kotlinx.serialization.Serializable

/**
 * Wire/config shape of the `level → rewards` table. A flat list of per-level
 * entries (rather than a `Map<Int, …>` or a polymorphic `LevelReward` array) so
 * it round-trips through plain JSON without string-keyed maps or a sealed-type
 * discriminator — each entry carries the at-most-one-of-each prizes a level can
 * hand out. Rides a single `JsonConfigValue` path (`progression.levelRewards`);
 * a missing/undecodable remote value falls back to [DefaultLevelRewards].
 */
@Serializable
data class LevelRewardsConfig(
    val levels: List<LevelRewardConfigEntry> = emptyList(),
) {
    fun rewardsForLevel(level: Int): List<LevelReward> =
        levels.firstOrNull { it.level == level }?.toRewards().orEmpty()
}

/**
 * One level's prizes. [chips] mints a chip prize; [xpBoostMs] gifts an XP boost
 * of that duration; [cosmeticProductId] grants the named catalog cosmetic via the
 * server's earned-grant allowlist. All three are optional and independent — a
 * level can grant any combination, or (absent from the table) nothing.
 */
@Serializable
data class LevelRewardConfigEntry(
    val level: Int,
    val chips: Long? = null,
    val xpBoostMs: Long? = null,
    val cosmeticProductId: String? = null,
) {
    fun toRewards(): List<LevelReward> = buildList {
        chips?.let { add(LevelReward.Chips(it)) }
        xpBoostMs?.let { add(LevelReward.XpBoost(it)) }
        cosmeticProductId?.let { add(LevelReward.Cosmetic(it)) }
    }
}

/**
 * Bundled default table — the starting recommendation, not a tuned economy.
 * Sparse and front-loaded so early levels feel rewarding without minting chips
 * endlessly at high levels; the human retunes it server-side via app-config.
 */
val DefaultLevelRewards: LevelRewardsConfig = LevelRewardsConfig(
    levels = listOf(
        LevelRewardConfigEntry(level = 3, chips = 1_000),
        LevelRewardConfigEntry(level = 5, chips = 2_500),
        LevelRewardConfigEntry(level = 7, chips = 4_000),
        LevelRewardConfigEntry(level = 10, chips = 7_500, xpBoostMs = XP_BOOST_DEFAULT_DURATION_MS),
        LevelRewardConfigEntry(level = 15, chips = 12_500),
        LevelRewardConfigEntry(level = 20, chips = 20_000, xpBoostMs = XP_BOOST_DEFAULT_DURATION_MS),
    ),
)

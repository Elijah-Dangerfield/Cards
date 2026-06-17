package com.dangerfield.cards.libraries.cards

/**
 * A prize granted for crossing a level. Each maps onto an existing offline-first
 * grant path — no new grant mechanism (see `docs/decisions.md` 2026-06-06,
 * level-up addendum):
 *  - [Chips] → the chips wallet ledger (idempotent via a `levelup_<level>` key).
 *  - [XpBoost] → extend the [XpBoostRepository] window.
 *
 * Cosmetic prizes (the achievement-reward grant path) are intentionally not
 * modeled yet — see the level-up rewards todo.
 */
sealed interface LevelReward {
    data class Chips(val amount: Long) : LevelReward
    data class XpBoost(val durationMs: Long = XP_BOOST_DEFAULT_DURATION_MS) : LevelReward
}

/**
 * Static `level → rewards` table. A level absent from the map grants nothing.
 *
 * **This table is a starting recommendation, not a tuned economy** — the
 * cadence (a chip prize every few early levels, an XP boost at the round
 * milestones) is a directional call for the human to tune. It's deliberately
 * sparse and front-loaded so early levels feel rewarding without minting chips
 * endlessly at high levels. Kept as static client content (mirrored server-side
 * on reconcile) so it works offline; promote to remote-config only if rewards
 * need tuning without a release.
 */
val LevelRewardTable: Map<Int, List<LevelReward>> = mapOf(
    3 to listOf(LevelReward.Chips(1_000)),
    5 to listOf(LevelReward.Chips(2_500)),
    7 to listOf(LevelReward.Chips(4_000)),
    10 to listOf(LevelReward.Chips(7_500), LevelReward.XpBoost()),
    15 to listOf(LevelReward.Chips(12_500)),
    20 to listOf(LevelReward.Chips(20_000), LevelReward.XpBoost()),
)

/** Rewards earned for [level], or empty if that level grants nothing. */
fun rewardsForLevel(level: Int): List<LevelReward> = LevelRewardTable[level].orEmpty()

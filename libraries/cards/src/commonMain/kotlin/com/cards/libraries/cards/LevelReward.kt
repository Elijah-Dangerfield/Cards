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

package com.dangerfield.cards.libraries.cards

/**
 * A prize granted for crossing a level. Each maps onto an existing offline-first
 * grant path — no new grant mechanism (see `docs/decisions.md` 2026-06-06,
 * level-up addendum):
 *  - [Chips] → the chips wallet ledger (idempotent via a `levelup_<level>` key).
 *  - [XpBoost] → add an inactive boost to the [XpBoostRepository] stash (the
 *    player lights it themselves; nothing auto-activates).
 *
 * Cosmetic prizes (the achievement-reward grant path) are intentionally not
 * modeled yet — see the level-up rewards todo.
 */
sealed interface LevelReward {
    data class Chips(val amount: Long) : LevelReward

    /**
     * Gifts one inactive XP boost. [durationMs] is retained for config/wire
     * compatibility but is currently informational: boosts are a uniform
     * consumable, so a gifted one runs for the standard window when lit, not for
     * this duration. A non-null `xpBoostMs` in config still drives *whether* a
     * level hands one out.
     */
    data class XpBoost(val durationMs: Long = XP_BOOST_DEFAULT_DURATION_MS) : LevelReward
}

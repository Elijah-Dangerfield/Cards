package com.dangerfield.cards.libraries.cards

/**
 * A prize granted for crossing a level. Each maps onto an existing grant path —
 * no bespoke grant mechanism (see `docs/decisions.md` 2026-06-06,
 * level-up addendum):
 *  - [Chips] → the chips wallet ledger (idempotent via a `levelup_<level>` key).
 *  - [XpBoost] → add an inactive boost to the [XpBoostRepository] stash (the
 *    player lights it themselves; nothing auto-activates).
 *  - [Cosmetic] → the same server-authoritative earned-grant path achievements
 *    use: a best-effort POST that records the cosmetic into inventory, idempotent
 *    on `(userId, productId)`. The server gates which products a level may grant,
 *    so a client can't self-grant an arbitrary cosmetic.
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

    /**
     * Grants the cosmetic with this catalog [productId] (felt / card back /
     * title / emote pack) via the server's earned-grant path. The server's
     * level-grant allowlist still decides whether the product is actually
     * grantable, mirroring the achievement-reward allowlist.
     */
    data class Cosmetic(val productId: String) : LevelReward
}

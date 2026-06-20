package com.dangerfield.cards.libraries.cards

/**
 * Server-tunable progression knobs, grouped behind one interface so consumers
 * read a clean API rather than wiring individual [com.dangerfield.cards.libraries.config.ConfiguredValue]s.
 * Same shape as `IdentityConfig`, but the impl resolves remote values (with a
 * bundled default) instead of hardcoding them — so the level economy can be
 * retuned without shipping a build. See `docs/decisions.md` 2026-06-17.
 *
 * The reward *grant* stays offline-first and idempotent (`LevelUpRewardGranter`);
 * this only relocates the table the granter reads from.
 */
interface ProgressionConfig {
    /** Prizes earned for crossing [level], or empty if that level grants nothing. */
    fun rewardsForLevel(level: Int): List<LevelReward>

    /**
     * The XP-per-level curve in force (server-tunable; [DefaultLevelCurve]
     * bundled). Derive levels through this on the authoritative grant /
     * persisted-counter paths so a retuned curve never lets display and grant
     * disagree.
     */
    fun levelCurve(): LevelCurve
}

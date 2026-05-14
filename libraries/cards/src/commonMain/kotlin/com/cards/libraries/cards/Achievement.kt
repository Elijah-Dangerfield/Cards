package com.dangerfield.cards.libraries.cards

/**
 * Static definition of an achievement — what it is, what unlocks it, what
 * it pays out. Defined in code (not the DB) so adding a new achievement is
 * a PR; user progress lives in [AchievementProgress].
 *
 * **Add a new achievement** by:
 *  1. Adding an [AchievementId] entry.
 *  2. Adding an [Achievement] to the registry with criteria + reward.
 *  3. Adding a clause in `AchievementEngine.handleHandFinished` if the
 *     criteria can't be expressed as one of the existing [Criterion] types.
 */
data class Achievement(
    val id: AchievementId,
    val name: String,
    val description: String,
    val icon: String,
    val rarity: AchievementRarity,
    val criterion: Criterion,
    val xpReward: Int,
    val chipReward: Long = 0L,
    val mode: AchievementMode = AchievementMode.EITHER,
)

/**
 * Stable string id for an achievement. Persisted in the DB; renaming an
 * existing id would lose user progress, so prefer adding a new one.
 */
enum class AchievementId {
    // Volume
    FIRST_HAND,
    HANDS_10,
    HANDS_100,
    HANDS_500,
    HANDS_1000,

    // Endurance
    NO_BUST_50,
    NO_BUST_100,

    // Hand-strength milestones (shown at showdown)
    SHOW_PAIR,
    SHOW_TWO_PAIR,
    SHOW_THREE_OF_KIND,
    SHOW_STRAIGHT,
    SHOW_FLUSH,
    SHOW_FULL_HOUSE,
    SHOW_FOUR_OF_KIND,
    SHOW_STRAIGHT_FLUSH,
    SHOW_ROYAL_FLUSH,

    // Pot-size milestones
    POT_500,
    POT_1000,
    POT_5000,

    // Tactical wins
    FIRST_WIN_BY_FOLD,
    WIN_BY_FOLD_10,
    GOOD_FOLD_FIRST,
    GOOD_FOLD_25,
    FIRST_ALL_IN,

    // Bot mastery
    BEAT_JANE_10,
    BEAT_DAVID_10,
    BEAT_GINA_10,
    BEAT_STEVE_10,
    BEAT_MIKE_10,

    // Difficulty
    CHALLENGING_FIRST_WIN,
    CHALLENGING_10_WINS,

    // Stack swings
    COMEBACK_FROM_5BB,
    DOUBLE_UP,
    TRIPLE_UP,

    // Level milestones
    REACH_LEVEL_5,
    REACH_LEVEL_10,
    REACH_LEVEL_25,
}

enum class AchievementRarity {
    /** ~60% of achievements. Bronze. +50 XP. */
    COMMON,

    /** ~30%. Silver. +200 XP. */
    RARE,

    /** ~8%. Gold + shimmer. +500 XP, often + chip reward. */
    EPIC,

    /** ~2%. Bragging rights. +2000 XP + chip reward. */
    LEGENDARY,
}

enum class AchievementMode {
    /** Earnable in both bot mode and multiplayer. */
    EITHER,

    /** Bot-only — typically "beat bot X 10 times" or difficulty-tier wins. */
    BOTS,

    /** Multiplayer-only — Elo-tier rewards, head-to-head wins, tournaments. */
    MULTIPLAYER,
}

/**
 * What it takes to unlock an achievement. Most map to a counter that ticks
 * up on each finished hand; [Custom] is the escape hatch for cross-hand
 * conditions (e.g. "no bust streak", "beat bot X 10 times").
 *
 * `target` is the value the underlying counter must reach.
 */
sealed class Criterion {
    abstract val target: Int

    /** Total hands finished. Played from any mode. */
    data class HandsPlayed(override val target: Int) : Criterion()

    /** Hands reaching showdown with at least the given hand category. */
    data class ShowAtLeast(val category: HandCategoryGrade, override val target: Int = 1) : Criterion()

    /** Hands won (any pot share). */
    data class HandsWon(override val target: Int) : Criterion()

    /**
     * Logic that doesn't fit the counters above. The `key` is unique per
     * criterion variant; the achievement engine knows how to read/write
     * that key in [AchievementProgress.customCounters]. Add a new key only
     * if no other [Criterion] subtype fits.
     */
    data class Custom(val key: String, override val target: Int) : Criterion()
}

/**
 * Live progress + earned timestamps for the current player.
 *
 * `counters` holds per-achievement counter values for criteria that can be
 * derived from "how many hands did X happen" (the simple cases). `earnedAt`
 * carries the absolute earned timestamp for already-earned achievements;
 * absent = not earned yet.
 *
 * `customCounters` is a flat map for one-off counters that don't map to a
 * single achievement — the "no-bust streak" length, the per-bot-personality
 * wins map, etc. Keys come from the [Criterion.Custom] definitions.
 */
data class AchievementProgress(
    val earned: Map<AchievementId, Long>,
    val counters: Map<AchievementId, Int>,
    val customCounters: Map<String, Int>,
) {
    fun isEarned(id: AchievementId): Boolean = id in earned

    companion object {
        val Empty: AchievementProgress = AchievementProgress(
            earned = emptyMap(),
            counters = emptyMap(),
            customCounters = emptyMap(),
        )
    }
}

/** XP awarded for an achievement, by rarity. */
val AchievementRarity.defaultXpReward: Int
    get() = when (this) {
        AchievementRarity.COMMON -> 50
        AchievementRarity.RARE -> 200
        AchievementRarity.EPIC -> 500
        AchievementRarity.LEGENDARY -> 2_000
    }

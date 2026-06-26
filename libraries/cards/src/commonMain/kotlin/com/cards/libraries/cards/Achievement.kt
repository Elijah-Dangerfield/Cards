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
    /**
     * When true, the achievement is hidden behind a "?" treatment on the
     * grid until the user earns it — a surprise, not a goal to grind. Default
     * `false` keeps achievements visible so players can see what's available
     * to chase.
     */
    val isMystery: Boolean = false,
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
    BOT_WHISPERER,

    // Difficulty
    CHALLENGING_FIRST_WIN,
    CHALLENGING_10_WINS,

    // Stack swings
    COMEBACK_FROM_5BB,
    DONT_CALL_IT_COMEBACK,
    DOUBLE_UP,
    TRIPLE_UP,

    // Busting opponents (bot-only for V1; MP variants land with Phase 4.2+)
    FIRST_BUST_DEALT,
    BUST_DEALT_5,

    // Busting opponents — MP siblings. Counter stays at 0 until Phase 4.2
    // server-authoritative hand resolution grants them server-side.
    FIRST_BUST_DEALT_MP,
    BUST_DEALT_5_MP,

    // MP-mode siblings of harder bot-friendly achievements. Counters stay
    // at 0 until Phase 4.2 server-authoritative gameplay lands; until then
    // the client can't witness which hands were multiplayer and the grant
    // endpoint refuses MP ids posted from clients (see
    // `ClientGrantableAchievements.Default.serverWitnessed`).
    FIRST_HAND_MP,
    HANDS_100_MP,
    WIN_BY_FOLD_10_MP,
    DOUBLE_UP_MP,
    TRIPLE_UP_MP,
    POT_5000_MP,

    // Level milestones
    REACH_LEVEL_5,
    REACH_LEVEL_10,
    REACH_LEVEL_25,

    // Onboarding
    TUTORIAL_COMPLETE,
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

/**
 * Current progress count for [this] achievement given the player's [progress]
 * snapshot. Reads the right map for the criterion type:
 *  - [Criterion.Custom] reads from `customCounters` keyed by the custom key.
 *  - All other criteria read from `counters` keyed by the achievement id.
 *
 * Clamped at [Criterion.target] so an earned achievement (counter often
 * keeps ticking past the threshold) renders as "target / target" rather
 * than the larger raw count.
 */
fun Achievement.currentProgress(progress: AchievementProgress): Int {
    val raw = when (val c = criterion) {
        is Criterion.Custom -> progress.customCounters[c.key] ?: 0
        else -> progress.counters[id] ?: 0
    }
    return raw.coerceAtMost(criterion.target)
}

/** True when the player's [progress] meets this achievement's threshold. */
fun Achievement.isMet(progress: AchievementProgress): Boolean =
    currentProgress(progress) >= criterion.target

/**
 * Build the display + unlock [AchievementProgress] from the player's **effective
 * achievement counters** (the server snapshot folded with the unsynced outbox —
 * see `PlayerStatsRepository.observeEffectiveCounters`) plus the synced [earned]
 * set and the current [level].
 *
 * This is the single source both the progress bars and the unlock engine read,
 * so they always agree, work offline, and survive reinstall. The server folds
 * its counters under the *same* keys the registry's [Criterion] uses
 * (`no_bust_streak`, `max_pot_seen`, `wins_vs_bot_<name>`, …) — that alignment is
 * the contract (PROG-1; the shared `:libraries:achievements` fold produces them).
 *
 * Counters that aren't folded from hand facts are supplied here: `current_level`
 * from [level] (XP-derived), and the bot-whisperer capstone derived from the
 * per-bot win family. Tutorial + multiplayer achievements have no fact counter,
 * so their bars read 0 until earned — correct, since they're tracked by the
 * earned set (tutorial) / granted server-side (MP).
 */
fun achievementProgressFrom(
    counters: Map<String, Long>,
    earned: Map<AchievementId, Long>,
    level: Int,
): AchievementProgress {
    fun clampInt(v: Long): Int = v.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val ints = counters.mapValues { clampInt(it.value) }

    val perAchievement = AllAchievements.mapNotNull { a ->
        when (val c = a.criterion) {
            is Criterion.HandsPlayed -> ints[SERVER_HANDS_PLAYED]
            is Criterion.HandsWon -> ints[SERVER_HANDS_WON]
            is Criterion.ShowAtLeast -> ints["$SERVER_SHOW_PREFIX${c.category.name}"]
            is Criterion.Custom -> null
        }?.let { a.id to it }
    }.toMap()

    val custom = ints.toMutableMap().apply {
        put(CURRENT_LEVEL, level)
        put(
            BOT_WHISPERER_BOTS_BEATEN,
            counters.count { (k, v) -> k.startsWith(winsVsBotKey("")) && v >= BOT_MASTERY_WINS },
        )
    }

    return AchievementProgress(earned = earned, counters = perAchievement, customCounters = custom)
}

/** Server counter keys the per-achievement criteria map onto (match the server fold). */
private const val SERVER_HANDS_PLAYED = "hands_played"
private const val SERVER_HANDS_WON = "hands_won"
private const val SERVER_SHOW_PREFIX = "show_"

/** Per-bot win threshold that counts toward the bot-whisperer capstone (matches BEAT_*_10). */
private const val BOT_MASTERY_WINS = 10L

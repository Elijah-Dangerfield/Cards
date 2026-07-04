package com.dangerfield.cards.server.domain

/**
 * Server-owned chip amounts for the client-witnessed reward triggers
 * (ENG-9): level-ups and single-player achievements.
 *
 * The client still *detects* these triggers offline (hand resolution and
 * the XP fold both run on-device today) and applies an optimistic local
 * credit for instant UI — but the wallet ledger only ever takes the
 * server's number, applied at the sync the server can see: the earned-
 * achievement record on `POST /v1/me/achievements/sync` and the XP total
 * crossing a rewarded level on `POST /v1/me/progression/sync`. A client-
 * asserted credit with a `levelup.*` / `achievement.*` reason on wallet
 * sync is refused outright ([isServerOwnedRewardCredit]).
 *
 * Same trade-off as [com.dangerfield.cards.server.game.DefaultServerWitnessedAchievements]
 * / `ClientGrantableAchievements`: the tables mirror the client's registry
 * by hand rather than through a shared module. If the amounts ever drift,
 * the server's number wins on the next balance overwrite — drift is a
 * transient display blip, never a mint. MP achievement chips are absent
 * on purpose: those are granted by the real server witness in
 * `DefaultServerWitnessedAchievements.CHIP_REWARDS`.
 */
object RewardChips {

    /** Reasons whose credits only the server may write. */
    private val SERVER_OWNED_CREDIT_REASON_PREFIXES = listOf("levelup.", "achievement.")

    fun isServerOwnedRewardCredit(delta: Long, reason: String): Boolean =
        delta > 0 && SERVER_OWNED_CREDIT_REASON_PREFIXES.any { reason.startsWith(it) }

    /**
     * Chip rewards for the client-reported single-player achievements —
     * mirrors the `chipReward` values in the client's `AchievementRegistry`.
     * Applied idempotently on [achievementLedgerKey], the same key shape
     * `DefaultServerWitnessedAchievements` uses for MP ids, so the two
     * grant paths share one ledger namespace and can never double-credit
     * the same achievement.
     */
    val ACHIEVEMENT_CHIPS: Map<String, Long> = mapOf(
        "HANDS_1000" to 1_000L,
        "NO_BUST_100" to 500L,
        "BOT_WHISPERER" to 1_000L,
        "CHALLENGING_10_WINS" to 500L,
        "COMEBACK_FROM_5BB" to 500L,
        "DONT_CALL_IT_COMEBACK" to 500L,
        "POT_5000" to 500L,
        "TRIPLE_UP" to 250L,
        "REACH_LEVEL_25" to 1_000L,
    )

    /**
     * Chip prize for crossing each rewarded level — mirrors the client's
     * bundled `DefaultLevelRewards` table (`:libraries:cards`). XP boosts
     * and cosmetics stay on their existing grant paths (boosts are client-
     * local, cosmetics ride the allowlisted `GrantsRoutes` endpoint);
     * only the chip component moves server-side.
     */
    val LEVEL_CHIPS: Map<Int, Long> = mapOf(
        3 to 1_000L,
        5 to 2_500L,
        7 to 4_000L,
        10 to 7_500L,
        15 to 12_500L,
        20 to 20_000L,
    )

    fun achievementLedgerKey(achievementId: String): String = "achievement:$achievementId"

    fun achievementLedgerReason(achievementId: String): String = "achievement_grant:$achievementId"

    fun levelLedgerKey(level: Int): String = "levelup:$level"

    fun levelLedgerReason(level: Int): String = "levelup_grant:$level"

    /**
     * Level for an XP total under the bundled quadratic curve (level N →
     * N+1 costs `100 × N²` XP) — the same math as the client's
     * `levelProgressFor` with `DefaultLevelCurve`. If the curve is ever
     * retuned via app-config, this must move with it (the client derives
     * grant levels through `ProgressionConfig.levelCurve()`).
     */
    fun levelForXp(totalXp: Long): Int {
        val xp = totalXp.coerceAtLeast(0)
        var level = 1
        var cumulative = 0L
        while (level < MAX_LEVEL) {
            val needed = BASE_XP * level * level
            if (cumulative + needed > xp) return level
            cumulative += needed
            level++
        }
        return MAX_LEVEL
    }

    /** Rewarded levels crossed moving from [beforeXp] to [afterXp], in order. */
    fun rewardedLevelsCrossed(beforeXp: Long, afterXp: Long): List<Int> {
        val before = levelForXp(beforeXp)
        val after = levelForXp(afterXp)
        if (after <= before) return emptyList()
        return ((before + 1)..after).filter { it in LEVEL_CHIPS }
    }

    private const val BASE_XP = 100L
    private const val MAX_LEVEL = 100
}

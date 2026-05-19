package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Extra signals the achievement engine needs at hand-end that aren't already
 * in [HandResultSummary]. Kept as a separate struct so we can extend it
 * without touching the (intentionally minimal) XP summary.
 */
data class AchievementHandContext(
    /**
     * Names of every bot at the table this hand. Multi-bot tables count a
     * win as a win against *every* bot present — slightly liberal but the
     * natural reading of "Beat Jane 10 times" when she's one of three bots
     * at the table.
     */
    val opponentBotNames: List<String>,
    /** Difficulty label of the bot table ("Casual" / "Standard" / "Challenging"). null in MP. */
    val botDifficulty: String?,
    /** Human's stack at the START of the hand. Used to detect "comeback from short stack." */
    val humanStartingStack: Long,
    /** Human's stack at the END of the hand. Used to detect "doubled up." */
    val humanEndingStack: Long,
    /** Big blind for the hand — used to translate stack sizes into BB-equivalents. */
    val bigBlind: Long,
    /**
     * Display names of opponents whose stack ended this hand at zero — i.e.
     * the players who got knocked out. The achievement engine attributes
     * busts to the human only when the human also won the pot this hand
     * (cheap, slightly-liberal attribution that's correct in the common
     * heads-up + short-handed-vs-bots cases).
     */
    val bustedOpponentNames: List<String> = emptyList(),
)

/**
 * Newly-earned achievement; the engine emits these from `recordHand` so
 * callers (UI, toast, telemetry) can react to them.
 */
data class EarnedAchievement(
    val achievement: Achievement,
    val earnedAtEpochMs: Long,
)

/**
 * Owns achievement progress + earned state for the current player.
 *
 * Local-only in V1; the schema mirrors what the Phase 3 server will hold so
 * the migration is a one-shot import on account claim (same pattern as
 * [ProgressionRepository]).
 */
interface AchievementRepository {

    fun observeProgress(): Flow<AchievementProgress>

    suspend fun getProgress(): AchievementProgress

    /**
     * Apply a finished hand to all relevant counters, evaluate criteria,
     * persist any newly-earned achievements, and award their XP/chip
     * rewards.
     *
     * Returns the achievements earned by *this* hand (zero or more). The
     * UI uses this to show a toast / celebratory moment.
     */
    suspend fun recordHand(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ): List<EarnedAchievement>

    /** Reset all achievement state. Used by "Fresh Start" / debug menus. */
    suspend fun deleteAll()
}

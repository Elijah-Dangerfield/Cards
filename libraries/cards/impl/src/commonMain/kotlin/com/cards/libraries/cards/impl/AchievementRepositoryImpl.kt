package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.CHALLENGING_WINS
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.COMEBACK_5BB
import com.dangerfield.cards.libraries.cards.Criterion
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandCategoryGrade
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.NO_BUST_STREAK
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.storage.db.AchievementCounterEntity
import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.AchievementEarnedEntity
import com.dangerfield.cards.libraries.cards.winsVsBotKey
import com.dangerfield.cards.libraries.core.logging.KLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AchievementRepositoryImpl(
    private val achievementDao: AchievementDao,
    private val progressionRepository: ProgressionRepository,
    private val chipsRepository: ChipsRepository,
    private val clock: Clock,
) : AchievementRepository {

    private val logger = KLog.withTag("AchievementRepository")

    override fun observeProgress(): Flow<AchievementProgress> = combine(
        achievementDao.observeEarned(),
        achievementDao.observeCounters(),
    ) { earnedRows, counterRows ->
        buildProgress(earnedRows, counterRows)
    }

    override suspend fun getProgress(): AchievementProgress = buildProgress(
        achievementDao.getEarned(),
        achievementDao.getCounters(),
    )

    override suspend fun recordHand(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ): List<EarnedAchievement> {
        // 1) Bump simple per-criterion counters.
        // Every finished hand: HandsPlayed-style achievements tick.
        AllAchievements.forEach { ach ->
            when (val c = ach.criterion) {
                is Criterion.HandsPlayed ->
                    achievementDao.incrementCounter(ach.id.name, 1)
                is Criterion.HandsWon ->
                    if (summary.wonPot) achievementDao.incrementCounter(ach.id.name, 1)
                is Criterion.ShowAtLeast -> {
                    val shown = summary.handCategory
                    if (summary.reachedShowdown &&
                        shown != null &&
                        shown.ordinal >= c.category.ordinal
                    ) {
                        achievementDao.incrementCounter(ach.id.name, 1)
                    }
                }
                is Criterion.Custom -> Unit // handled below
            }
        }

        // 2) Update custom counters (no-bust streak, per-bot wins, etc.).
        updateNoBustStreak(context)
        updatePerBotWinsCounter(summary, context)
        updateChallengingWinsCounter(summary, context)
        updateComebackCounter(summary, context)

        // 3) Re-read counters once, then check every un-earned achievement.
        val counters = achievementDao.getCounters().associate { it.key to it.value }
        val alreadyEarned = achievementDao.getEarned().map { it.achievementId }.toSet()
        val now = clock.now().toEpochMilliseconds()

        val newlyEarned = mutableListOf<EarnedAchievement>()
        for (ach in AllAchievements) {
            if (ach.id.name in alreadyEarned) continue
            if (!modeAllows(ach, summary.mode)) continue
            if (!ach.criterion.isMet(ach.id, counters)) continue

            achievementDao.insertEarned(
                AchievementEarnedEntity(achievementId = ach.id.name, earnedAtEpochMs = now),
            )
            newlyEarned += EarnedAchievement(achievement = ach, earnedAtEpochMs = now)
            logger.i { "Achievement earned: ${ach.id.name} (${ach.name})" }
        }

        // 4) Award rewards for the freshly-earned achievements.
        if (newlyEarned.isNotEmpty()) {
            val xpDelta = newlyEarned.sumOf { it.achievement.xpReward }
            val chipDelta = newlyEarned.sumOf { it.achievement.chipReward }
            if (xpDelta > 0) progressionRepository.applyAchievementXp(xpDelta)
            if (chipDelta > 0L) chipsRepository.applyDelta(chipDelta)
        }

        return newlyEarned
    }

    override suspend fun deleteAll() {
        achievementDao.deleteAllEarned()
        achievementDao.deleteAllCounters()
    }

    private fun modeAllows(ach: Achievement, mode: XpMode): Boolean = when (ach.mode) {
        com.dangerfield.cards.libraries.cards.AchievementMode.EITHER -> true
        com.dangerfield.cards.libraries.cards.AchievementMode.BOTS -> mode == XpMode.BOTS
        com.dangerfield.cards.libraries.cards.AchievementMode.MULTIPLAYER -> mode == XpMode.MULTIPLAYER
    }

    private fun Criterion.isMet(achievementId: AchievementId, counters: Map<String, Int>): Boolean {
        val value = when (this) {
            is Criterion.Custom -> counters[key] ?: 0
            else -> counters[achievementId.name] ?: 0
        }
        return value >= target
    }

    private suspend fun updateNoBustStreak(context: AchievementHandContext) {
        // "Bust" = ending the hand with 0 chips. Streak resets to 0 on bust,
        // otherwise increments by 1 (the just-finished survived hand).
        if (context.humanEndingStack <= 0L) {
            achievementDao.setCounter(AchievementCounterEntity(key = NO_BUST_STREAK, value = 0))
        } else {
            achievementDao.incrementCounter(NO_BUST_STREAK, 1)
        }
    }

    private suspend fun updatePerBotWinsCounter(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ) {
        if (!summary.wonPot) return
        for (bot in context.opponentBotNames) {
            achievementDao.incrementCounter(winsVsBotKey(bot), 1)
        }
    }

    private suspend fun updateChallengingWinsCounter(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ) {
        if (!summary.wonPot) return
        if (!context.botDifficulty.equals("Challenging", ignoreCase = true)) return
        achievementDao.incrementCounter(CHALLENGING_WINS, 1)
    }

    private suspend fun updateComebackCounter(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ) {
        // "Comeback" = started the hand with <= 5 BB and ended with >= 2× the
        // starting stack. Captures the "I was almost out and doubled up" moment.
        val bb = context.bigBlind
        if (bb <= 0) return
        val startBb = context.humanStartingStack / bb
        if (startBb > 5) return
        if (context.humanEndingStack < context.humanStartingStack * 2) return
        achievementDao.incrementCounter(COMEBACK_5BB, 1)
    }

    private fun buildProgress(
        earnedRows: List<AchievementEarnedEntity>,
        counterRows: List<AchievementCounterEntity>,
    ): AchievementProgress {
        val earned = earnedRows.mapNotNull { row ->
            val id = runCatching { AchievementId.valueOf(row.achievementId) }.getOrNull()
            id?.let { it to row.earnedAtEpochMs }
        }.toMap()

        val perAchievementCounters = mutableMapOf<AchievementId, Int>()
        val customCounters = mutableMapOf<String, Int>()
        for (row in counterRows) {
            val asAchievementId = runCatching { AchievementId.valueOf(row.key) }.getOrNull()
            if (asAchievementId != null && asAchievementId in AllAchievementsById) {
                perAchievementCounters[asAchievementId] = row.value
            } else {
                customCounters[row.key] = row.value
            }
        }
        return AchievementProgress(
            earned = earned,
            counters = perAchievementCounters,
            customCounters = customCounters,
        )
    }
}

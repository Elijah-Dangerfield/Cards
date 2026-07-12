package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementGrantApi
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementMode
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PlayerStatsRepository
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.achievementProgressFrom
import com.dangerfield.cards.libraries.cards.isMet
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.AchievementEarnedEntity
import com.dangerfield.cards.libraries.cards.impl.dto.AchievementsSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.AchievementsSyncResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.EarnedAchievementDto
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Achievements over the server-authoritative counters.
 *
 * Progress is **not** accumulated locally. Both display and unlock read one
 * source — the **effective counters** from [PlayerStatsRepository]
 * (`server snapshot folded with the unsynced outbox`, via the shared
 * `AchievementCounters.fold`). That's what makes progress agree everywhere,
 * survive reinstall (a fresh client folds an empty outbox onto the server
 * snapshot → exact server truth), and still work offline (server-last-known +
 * this session's hands). This repo only owns the **earned** set (synced to the
 * server) and the optimistic reward grant on unlock.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AchievementRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = UserScopedSyncer::class)
@Inject
class AchievementRepositoryImpl(
    private val achievementDao: AchievementDao,
    private val playerStatsRepository: PlayerStatsRepository,
    private val progressionRepository: ProgressionRepository,
    private val chipsRepository: ChipsRepository,
    private val grantApi: AchievementGrantApi,
    private val inventoryRepository: InventoryRepository,
    private val networkClient: NetworkClient,
    private val progressionConfig: ProgressionConfig,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : AchievementRepository, UserScopedSyncer {

    private val logger = KLog.withTag("AchievementRepository")
    private val syncLogger = KLog.withTag("AchievementSync")
    private val syncMutex = Mutex()

    override fun observeProgress(): Flow<AchievementProgress> = combine(
        achievementDao.observeEarned(),
        playerStatsRepository.observeEffectiveCounters(),
        progressionRepository.observeProgression(),
    ) { earnedRows, counters, progression ->
        achievementProgressFrom(
            counters = counters,
            earned = earnedMap(earnedRows.map { it.achievementId to it.earnedAtEpochMs }),
            level = levelFor(progression.totalXp),
        )
    }

    override suspend fun getProgress(): AchievementProgress = achievementProgressFrom(
        counters = playerStatsRepository.effectiveCounters(),
        earned = earnedMap(achievementDao.getEarned().map { it.achievementId to it.earnedAtEpochMs }),
        level = levelFor(progressionRepository.getProgression().totalXp),
    )

    override suspend fun recordHand(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ): List<EarnedAchievement> {
        // The hand's facts are already in the player-stats outbox by now (the
        // play VM records them before us), so the effective counters include this
        // hand. We just read them and award anything newly crossed — no local
        // counting, so a reinstalled client unlocks correctly off server truth.
        val progress = achievementProgressFrom(
            counters = playerStatsRepository.effectiveCounters(),
            earned = earnedMap(achievementDao.getEarned().map { it.achievementId to it.earnedAtEpochMs }),
            level = levelFor(progressionRepository.getProgression().totalXp),
        )
        val alreadyEarned = progress.earned.keys.map { it.name }.toSet()

        // Eligible = criterion met and not earned. Cap per-hand unlocks so a new
        // player isn't flooded; anything over the cap stays eligible (it's still
        // met) and unlocks next hand. Award lowest-rarity/XP first so players
        // climb common → epic over hands rather than getting big payouts up front.
        val ordered = AllAchievements
            .filter { it.id.name !in alreadyEarned && modeAllows(it, summary.mode) && it.isMet(progress) }
            .sortedWith(compareBy({ it.rarity.ordinal }, { it.xpReward }))
        val toAward = ordered.take(MAX_ACHIEVEMENTS_PER_HAND)
        if (ordered.size > toAward.size) {
            logger.d { "Deferred ${ordered.size - toAward.size} achievements (per-hand cap)" }
        }

        val now = clock.now().toEpochMilliseconds()
        val newlyEarned = toAward.map { ach ->
            achievementDao.insertEarned(AchievementEarnedEntity(achievementId = ach.id.name, earnedAtEpochMs = now))
            logger.i { "Achievement earned: ${ach.id.name} (${ach.name})" }
            EarnedAchievement(achievement = ach, earnedAtEpochMs = now)
        }

        if (newlyEarned.isNotEmpty()) grantRewards(newlyEarned)
        return newlyEarned
    }

    override suspend fun recordTutorialComplete(): EarnedAchievement? {
        // Idempotent — replaying the tutorial from Settings doesn't re-grant.
        val id = AchievementId.TUTORIAL_COMPLETE
        if (achievementDao.getEarned().any { it.achievementId == id.name }) return null

        val now = clock.now().toEpochMilliseconds()
        achievementDao.insertEarned(AchievementEarnedEntity(achievementId = id.name, earnedAtEpochMs = now))
        val achievement = AllAchievementsById[id] ?: return null
        if (achievement.xpReward > 0) {
            progressionRepository.applyAchievementXp(delta = achievement.xpReward, description = achievement.name)
        }
        logger.i { "Achievement earned: ${id.name} (${achievement.name})" }
        return EarnedAchievement(achievement = achievement, earnedAtEpochMs = now)
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        // Always POST — an empty earned list is a valid "hydrate set" call, how a
        // reinstall / second device pulls down achievements earned elsewhere.
        networkClient.authedCall("achievements.sync", retry = RetryPolicy.idempotent()) { client ->
            val unsynced = achievementDao.getUnsyncedEarned()
            val request = AchievementsSyncRequestDto(
                earned = unsynced.map {
                    EarnedAchievementDto(achievementId = it.achievementId, earnedAtEpochMs = it.earnedAtEpochMs)
                },
            )
            val response: AchievementsSyncResponseDto = client
                .post("/v1/me/achievements/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()

            if (unsynced.isNotEmpty()) {
                achievementDao.markEarnedSynced(unsynced.map { it.achievementId })
            }
            // Reconcile: insert any server-earned id we don't have (earned on
            // another device) as already-synced. Achievements are monotonic.
            val localIds = achievementDao.getEarned().map { it.achievementId }.toSet()
            response.earned
                .filter { it.achievementId !in localIds }
                .forEach { server ->
                    achievementDao.insertEarned(
                        AchievementEarnedEntity(
                            achievementId = server.achievementId,
                            earnedAtEpochMs = server.earnedAtEpochMs,
                            synced = true,
                        ),
                    )
                }
            // The server minted achievement chips during this sync (ENG-9 —
            // wallet sync refuses the client's own `achievement.*` credit).
            // Re-pull the wallet so the reward is visible now, not at the next
            // trigger edge (PROG-12); a pull issued after the mint is
            // ordering-safe against any concurrent wallet sync.
            if (response.walletBalance != null) {
                syncLogger.i { "Server minted achievement chips — re-pulling the wallet" }
                Catching { chipsRepository.sync() }
                    .logOnFailure { "Wallet re-pull after achievement-chip mint failed; the next sync edge heals it" }
            }

            syncLogger.d { "Sync complete: ${unsynced.size} sent, ${response.earned.size} server-earned." }
            Unit
        }
    }

    override suspend fun deleteAll() {
        achievementDao.deleteAllEarned()
    }

    /**
     * Award XP + chips for freshly-earned achievements and tell the server (so
     * its reward mapping can grant any cosmetic into inventory). One XP ledger row
     * per achievement so the recent-XP feed names it. Grants are idempotent
     * (keyed per achievement). The server dispatch is fired in [appScope] so a VM
     * teardown mid-hand-result can't drop it.
     */
    private suspend fun grantRewards(newlyEarned: List<EarnedAchievement>) {
        for (earned in newlyEarned) {
            if (earned.achievement.xpReward > 0) {
                progressionRepository.applyAchievementXp(
                    delta = earned.achievement.xpReward,
                    description = earned.achievement.name,
                )
            }
        }
        for (earned in newlyEarned) {
            if (earned.achievement.chipReward > 0L) {
                chipsRepository.addChips(
                    amount = earned.achievement.chipReward,
                    reason = "achievement.${earned.achievement.id.name}",
                    idempotencyKey = "achievement.${earned.achievement.id.name}",
                )
            }
        }
        appScope.launch {
            Catching {
                var anyGranted = false
                for (earned in newlyEarned) {
                    if (grantApi.grantAchievement(earned.achievement.id)) anyGranted = true
                }
                if (anyGranted) inventoryRepository.sync()
            }.onFailure { logger.w(it) { "Achievement-grant dispatch failed; will retry next sync." } }
        }
    }

    private fun modeAllows(ach: Achievement, mode: XpMode): Boolean = when (ach.mode) {
        AchievementMode.EITHER -> true
        AchievementMode.BOTS -> mode == XpMode.BOTS
        AchievementMode.MULTIPLAYER -> mode == XpMode.MULTIPLAYER
    }

    private suspend fun levelFor(totalXp: Long): Int =
        levelProgressFor(totalXp, progressionConfig.levelCurve()).level

    private fun earnedMap(rows: List<Pair<String, Long>>): Map<AchievementId, Long> =
        rows.mapNotNull { (idName, at) ->
            Catching { AchievementId.valueOf(idName) }.getOrNull()?.let { it to at }
        }.toMap()

    private companion object {
        /** How many achievements can unlock from a single finished hand. */
        const val MAX_ACHIEVEMENTS_PER_HAND: Int = 2
    }
}

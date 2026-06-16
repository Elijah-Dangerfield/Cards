package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementGrantApi
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.AchievementHandContext
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AchievementRepository
import com.dangerfield.cards.libraries.cards.ALL_IN_HANDS
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.BOT_WHISPERER_BOTS_BEATEN
import com.dangerfield.cards.libraries.cards.BUSTS_DEALT
import com.dangerfield.cards.libraries.cards.CHALLENGING_WINS
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.COMEBACK_5BB
import com.dangerfield.cards.libraries.cards.DONT_CALL_IT_COMEBACK_COUNTER
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.SHORT_STACK_ARMED
import com.dangerfield.cards.libraries.cards.CURRENT_LEVEL
import com.dangerfield.cards.libraries.cards.Criterion
import com.dangerfield.cards.libraries.cards.DOUBLED_UP
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.GOOD_FOLD
import com.dangerfield.cards.libraries.cards.HandCategoryGrade
import com.dangerfield.cards.libraries.cards.HandResultSummary
import com.dangerfield.cards.libraries.cards.MAX_POT_BB_RATIO
import com.dangerfield.cards.libraries.cards.MAX_POT_SEEN
import com.dangerfield.cards.libraries.cards.NO_BUST_STREAK
import com.dangerfield.cards.libraries.cards.TUTORIAL_COMPLETE
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.TRIPLED_UP
import com.dangerfield.cards.libraries.cards.WIN_BY_FOLD
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.cards.storage.db.AchievementCounterEntity
import com.dangerfield.cards.libraries.cards.storage.db.AchievementDao
import com.dangerfield.cards.libraries.cards.storage.db.AchievementEarnedEntity
import com.dangerfield.cards.libraries.cards.impl.dto.AchievementsSyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.AchievementsSyncResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.EarnedAchievementDto
import com.dangerfield.cards.libraries.cards.winsVsBotKey
import com.dangerfield.cards.libraries.core.Catching
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

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AchievementRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class AchievementRepositoryImpl(
    private val achievementDao: AchievementDao,
    private val progressionRepository: ProgressionRepository,
    private val chipsRepository: ChipsRepository,
    private val grantApi: AchievementGrantApi,
    private val inventoryRepository: InventoryRepository,
    private val networkClient: NetworkClient,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : AchievementRepository, AppEventListener {

    private val logger = KLog.withTag("AchievementRepository")
    private val syncLogger = KLog.withTag("AchievementSync")
    private val syncMutex = Mutex()

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
        updateDontCallItComebackCounter(context)
        updateWinByFoldCounter(summary)
        updateGoodFoldCounter(summary)
        updateAllInCounter(summary)
        updateMaxPotSeen(summary, context)
        updateStackSwingCounters(context)
        updateBustsDealtCounter(summary, context)
        updateCurrentLevel()

        // 3) Re-read counters once, then check every un-earned achievement.
        val counters = achievementDao.getCounters().associate { it.key to it.value }
        val alreadyEarned = achievementDao.getEarned().map { it.achievementId }.toSet()
        val now = clock.now().toEpochMilliseconds()

        // Eligible = criterion met this hand and not earned previously. We
        // CAP how many actually unlock per hand so a new player doesn't get
        // flooded with 5+ achievements in their first session (a flood reads
        // as a one-time confetti rather than ongoing motivation). Anything
        // over the cap stays eligible — its criterion will still pass next
        // hand and it'll unlock then.
        val eligible = AllAchievements.filter { ach ->
            ach.id.name !in alreadyEarned &&
                modeAllows(ach, summary.mode) &&
                ach.criterion.isMet(ach.id, counters)
        }
        // Award lower-rarity / lower-XP first so the user climbs from common
        // → epic over multiple hands instead of getting their biggest
        // payouts up front.
        val ordered = eligible.sortedWith(
            compareBy({ it.rarity.ordinal }, { it.xpReward }),
        )
        val toAwardThisHand = ordered.take(MAX_ACHIEVEMENTS_PER_HAND)

        val newlyEarned = mutableListOf<EarnedAchievement>()
        for (ach in toAwardThisHand) {
            achievementDao.insertEarned(
                AchievementEarnedEntity(achievementId = ach.id.name, earnedAtEpochMs = now),
            )
            newlyEarned += EarnedAchievement(achievement = ach, earnedAtEpochMs = now)
            logger.i { "Achievement earned: ${ach.id.name} (${ach.name})" }
        }
        if (ordered.size > toAwardThisHand.size) {
            logger.d {
                "Deferred ${ordered.size - toAwardThisHand.size} achievements to next hand " +
                    "(per-hand cap = $MAX_ACHIEVEMENTS_PER_HAND)"
            }
        }

        // 4) Award rewards for the freshly-earned achievements. One XP ledger
        //    row per achievement so the recent-XP feed shows the specific
        //    name ("Achievement unlocked · Royal flush") instead of an opaque
        //    aggregated "+700 XP".
        if (newlyEarned.isNotEmpty()) {
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
                        // achievementId+earnedAt is unique per user per
                        // unlock — collapsing both into one key gives us
                        // safe replay across the bounce-the-app-mid-grant
                        // window without double-crediting.
                        reason = "achievement.${earned.achievement.id.name}",
                        idempotencyKey = "achievement.${earned.achievement.id.name}",
                    )
                }
            }
            // 5) Tell the server about each freshly-earned achievement so the
            //    server-side AchievementRewards mapping can grant any cosmetic
            //    into inventory (unlock-only titles, future legendary felts).
            //    Most achievements have no reward → 204 + return false; only
            //    when something actually got granted do we trigger an inventory
            //    sync to pull the new row into the local DB.
            //
            //    Fired in appScope so it outlives this hand's call site (VM
            //    teardown mid-hand-result must not drop the grant). Wrapped in
            //    Catching for belt-and-suspenders — the API impl is supposed
            //    to swallow failures itself, but we don't want a surprise to
            //    interrupt the XP/chip awards above.
            appScope.launch {
                Catching {
                    var anyGranted = false
                    for (earned in newlyEarned) {
                        if (grantApi.grantAchievement(earned.achievement.id)) {
                            anyGranted = true
                        }
                    }
                    if (anyGranted) inventoryRepository.sync()
                }.onFailure {
                    logger.w(it) { "Achievement-grant dispatch failed; will retry next sync." }
                }
            }
        }

        return newlyEarned
    }

    override suspend fun recordTutorialComplete(): EarnedAchievement? {
        // Idempotent. The user can replay the tutorial from Settings; the
        // grant only happens the first time. We check earned BEFORE
        // bumping the counter so a replay doesn't quietly mark the
        // sticky flag a second time either.
        val id = AchievementId.TUTORIAL_COMPLETE
        val alreadyEarned = achievementDao.getEarned()
            .any { it.achievementId == id.name }
        if (alreadyEarned) return null

        achievementDao.setCounter(
            AchievementCounterEntity(key = TUTORIAL_COMPLETE, value = 1),
        )
        val now = clock.now().toEpochMilliseconds()
        achievementDao.insertEarned(
            AchievementEarnedEntity(achievementId = id.name, earnedAtEpochMs = now),
        )
        val achievement = AllAchievementsById[id]
            ?: return null // registry drift — log + bail rather than NPE
        if (achievement.xpReward > 0) {
            progressionRepository.applyAchievementXp(
                delta = achievement.xpReward,
                description = achievement.name,
            )
        }
        logger.i { "Achievement earned: ${id.name} (${achievement.name})" }
        return EarnedAchievement(achievement = achievement, earnedAtEpochMs = now)
    }

    override suspend fun sync(): Result<Unit> = syncMutex.withLock {
        // Always POST — an empty earned list is a valid "hydrate set" call,
        // which is how a reinstall / second device pulls down achievements
        // earned elsewhere.
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

            // Our posted rows are now on the server — mark them synced (kept
            // locally as the source of truth for the achievement list).
            if (unsynced.isNotEmpty()) {
                achievementDao.markEarnedSynced(unsynced.map { it.achievementId })
            }

            // Reconcile: insert any server-earned id we don't have locally
            // (earned on another device) as already-synced. Achievements are
            // monotonic, so we never remove a local earned row.
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

            syncLogger.d {
                "Sync complete: ${unsynced.size} sent, ${response.earned.size} server-earned."
            }
            Unit
        }
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // A user just became active (cold-boot resolve, sign-in, or account
        // switch). On a switch the prior user's earned set was just wiped, so
        // re-hydrate the new user's now. Sign-out (current == null) fetches
        // nothing.
        if (event.current == null) return
        appScope.launch { sync() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        // Cold-boot's initial sync is owned by [onUserChanged]; this handles
        // the warm-resume reconcile only.
        if (event.isColdBoot) return
        appScope.launch { sync() }
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
        // Same bot can appear in the lineup twice on busy MP-style tables;
        // dedup so the capstone counter ticks at most once per bot per hand.
        for (bot in context.opponentBotNames.distinct()) {
            val key = winsVsBotKey(bot)
            val previous = achievementDao.getCounter(key) ?: 0
            achievementDao.incrementCounter(key, 1)
            // First time this bot's per-bot counter crosses the BEAT_X_10
            // threshold, tick the Bot Whisperer capstone — fires once each
            // per-bot count goes from 9 to 10.
            if (previous == BEAT_BOT_10_THRESHOLD - 1) {
                achievementDao.incrementCounter(BOT_WHISPERER_BOTS_BEATEN, 1)
            }
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

    private suspend fun updateDontCallItComebackCounter(context: AchievementHandContext) {
        // "Don't call it a comeback" = the human's stack dipped to <=10 BB
        // at some point, then climbed back to a full ~100 BB stack. BB-relative
        // so the criterion reads the same on Practice/Casual/Standard/High/Premium
        // tiers; the absolute 100/1000 chip thresholds were broken at the higher
        // tiers (100 chips on High = 0.5 BB; 1000 chips = 5 BB). Armed flag
        // persists across hands; once recovery triggers, the flag resets so
        // subsequent dips can re-arm later runs.
        val bb = context.bigBlind
        if (bb <= 0) return
        val ending = context.humanEndingStack
        val armed = (achievementDao.getCounter(SHORT_STACK_ARMED) ?: 0) > 0
        val shortStackCeil = 10L * bb
        val fullStackFloor = 100L * bb
        if (armed && ending >= fullStackFloor) {
            achievementDao.incrementCounter(DONT_CALL_IT_COMEBACK_COUNTER, 1)
            achievementDao.setCounter(
                AchievementCounterEntity(key = SHORT_STACK_ARMED, value = 0),
            )
        }
        // Re-arm AFTER the recovery check so a single hand can't both dip
        // and recover (would defeat the purpose). `> 0` excludes the bust
        // case — bust already has its own modal + auto-rebuy story.
        if (ending in 1L..shortStackCeil) {
            achievementDao.setCounter(
                AchievementCounterEntity(key = SHORT_STACK_ARMED, value = 1),
            )
        }
    }

    private suspend fun updateWinByFoldCounter(summary: HandResultSummary) {
        if (summary.wonByFold) achievementDao.incrementCounter(WIN_BY_FOLD, 1)
    }

    private suspend fun updateGoodFoldCounter(summary: HandResultSummary) {
        // "Good fold" = the human folded, the hand went to showdown, and their
        // would-have-been hand at the river would have lost. Rewards bankroll
        // discipline rather than just engagement.
        if (summary.foldedHandWouldHaveLost) achievementDao.incrementCounter(GOOD_FOLD, 1)
    }

    private suspend fun updateAllInCounter(summary: HandResultSummary) {
        if (summary.humanWasAllIn) achievementDao.incrementCounter(ALL_IN_HANDS, 1)
    }

    private suspend fun updateMaxPotSeen(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ) {
        if (summary.totalPot <= 0) return
        // High-water marks: only ratchet up. Avoid increment — we want max,
        // not sum. Two counters: absolute pot (used by POT_500 / POT_1000
        // where chip thresholds read fine across tiers) and pot-as-BB-multiple
        // (used by POT_5000 so the Whale-pot read is tier-agnostic).
        val current = achievementDao.getCounter(MAX_POT_SEEN) ?: 0
        if (summary.totalPot.toInt() > current) {
            achievementDao.setCounter(
                AchievementCounterEntity(key = MAX_POT_SEEN, value = summary.totalPot.toInt()),
            )
        }
        val bb = context.bigBlind
        if (bb > 0) {
            val ratio = (summary.totalPot / bb).toInt()
            val currentRatio = achievementDao.getCounter(MAX_POT_BB_RATIO) ?: 0
            if (ratio > currentRatio) {
                achievementDao.setCounter(
                    AchievementCounterEntity(key = MAX_POT_BB_RATIO, value = ratio),
                )
            }
        }
    }

    private suspend fun updateBustsDealtCounter(
        summary: HandResultSummary,
        context: AchievementHandContext,
    ) {
        // Attribute busts to the human only on hands where the human won at
        // least one pot share. Otherwise an opponent who busted to a third
        // player would still credit the human's "Eliminator" progress —
        // wrong. Cheap, correct-enough heuristic for the common case.
        if (!summary.wonPot) return
        val busts = context.bustedOpponentCount
        if (busts == 0) return
        achievementDao.incrementCounter(BUSTS_DEALT, busts)
    }

    private suspend fun updateStackSwingCounters(context: AchievementHandContext) {
        // Doubled / tripled up = ended a hand with 2× / 3× the starting stack.
        // Count occurrences rather than gate on first — over time these become
        // "X times you doubled up" trivia, which we may surface in stats later.
        if (context.humanStartingStack <= 0L) return
        if (context.humanEndingStack >= context.humanStartingStack * 2L) {
            achievementDao.incrementCounter(DOUBLED_UP, 1)
        }
        if (context.humanEndingStack >= context.humanStartingStack * 3L) {
            achievementDao.incrementCounter(TRIPLED_UP, 1)
        }
    }

    private suspend fun updateCurrentLevel() {
        // Mirror the current level from progression into the achievement-counter
        // table so level-threshold criteria evaluate uniformly with everything
        // else. Read AFTER `awardForHand` in the caller — the XP for this
        // hand needs to land before we compute level here.
        val xp = progressionRepository.getProgression().totalXp
        val level = levelProgressFor(xp).level
        achievementDao.setCounter(
            AchievementCounterEntity(key = CURRENT_LEVEL, value = level),
        )
    }

    private companion object {
        /** How many achievements can unlock from a single finished hand.
         *  Caps the "everything popped at once" flood new players hit on
         *  their first session — anything over the cap stays eligible and
         *  unlocks on a subsequent hand. */
        const val MAX_ACHIEVEMENTS_PER_HAND: Int = 2

        /** Per-bot wins required for each BEAT_X_10 achievement; mirrored
         *  here so the capstone counter can detect when a per-bot count
         *  crosses the threshold for the first time. */
        const val BEAT_BOT_10_THRESHOLD: Int = 10
    }

    private fun buildProgress(
        earnedRows: List<AchievementEarnedEntity>,
        counterRows: List<AchievementCounterEntity>,
    ): AchievementProgress {
        val earned = earnedRows.mapNotNull { row ->
            val id = Catching { AchievementId.valueOf(row.achievementId) }.getOrNull()
            id?.let { it to row.earnedAtEpochMs }
        }.toMap()

        val perAchievementCounters = mutableMapOf<AchievementId, Int>()
        val customCounters = mutableMapOf<String, Int>()
        for (row in counterRows) {
            val asAchievementId = Catching { AchievementId.valueOf(row.key) }.getOrNull()
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

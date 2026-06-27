package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.LevelCosmeticGrantApi
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.ProgressionConfig
import com.dangerfield.cards.libraries.cards.ProgressionRepository
import com.dangerfield.cards.libraries.cards.XpBoostRepository
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Grants the `level → reward` table's prizes when the player's derived level
 * crosses a rewarded level. Offline-first by construction: level is derived
 * locally from `total_xp`, the prize is theirs the moment they level (no server
 * round-trip needed), and chip grants are idempotent via a `levelup_<level>`
 * wallet-ledger key. See `docs/decisions.md` 2026-06-06 (level-up addendum).
 *
 * `highestLevelRewarded == 0` is the unset sentinel: the first observed level
 * seeds the watermark **without** granting, so a fresh install / account switch
 * / reinstall never retro-grants rewards for levels already held. Boots via
 * [AutoInit] so it's watching progression before the user touches a screen.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class LevelUpRewardGranter(
    private val progressionRepository: ProgressionRepository,
    private val chipsRepository: ChipsRepository,
    private val xpBoostRepository: XpBoostRepository,
    private val cosmeticGrantApi: LevelCosmeticGrantApi,
    private val inventoryRepository: InventoryRepository,
    private val progressionConfig: ProgressionConfig,
    private val appCache: AppCache,
    private val appScope: AppCoroutineScope,
) : AutoInit {

    private val logger = KLog.withTag("LevelUpRewardGranter")
    private val mutex = Mutex()

    init {
        appScope.launch {
            progressionRepository.observeProgression()
                .map { levelProgressFor(it.totalXp, progressionConfig.levelCurve()).level }
                .distinctUntilChanged()
                .collect { level -> reconcile(level) }
        }
    }

    private suspend fun reconcile(currentLevel: Int) = mutex.withLock {
        val watermark = appCache.get().highestLevelRewarded
        if (watermark == 0) {
            // Unset — seed to the current level so we never retro-grant. The
            // granter is the single AutoInit observer that reliably sees the
            // pre-session level before the user touches a screen, so we anchor
            // the celebration watermark here too (when it is likewise unset).
            // HomeViewModel's gate also seeds on `0`, but it can race behind a
            // level-up earned this session and seed straight past it, silently
            // eating the celebration (PROG-3). Anchoring both from this one
            // early observer removes that race; the gate's seed stays as a
            // fallback for the (unlikely) case it observes first.
            appCache.update {
                it.copy(
                    highestLevelRewarded = currentLevel,
                    lastCelebratedLevel = if (it.lastCelebratedLevel == 0) {
                        currentLevel
                    } else {
                        it.lastCelebratedLevel
                    },
                )
            }
            return@withLock
        }
        if (currentLevel <= watermark) return@withLock

        for (level in (watermark + 1)..currentLevel) {
            grantRewardsFor(level)
            // Advance per level so a crash mid-loop only ever re-touches the
            // in-flight level (and chip grants are key-idempotent anyway).
            appCache.update { it.copy(highestLevelRewarded = level) }
        }
    }

    private suspend fun grantRewardsFor(level: Int) {
        val rewards = progressionConfig.rewardsForLevel(level)
        if (rewards.isEmpty()) return
        for (reward in rewards) {
            Catching {
                when (reward) {
                    is LevelReward.Chips -> chipsRepository.addChips(
                        amount = reward.amount,
                        reason = "levelup.$level",
                        idempotencyKey = "levelup_$level",
                    )
                    // Gifted boosts land in the stash, inactive — the player
                    // lights them from their profile when they're ready, same as
                    // a shop-bought one. Nothing auto-activates.
                    is LevelReward.XpBoost -> xpBoostRepository.grant()
                    // Cosmetics are server-owned inventory: POST the grant (the
                    // server's level-grant allowlist gates it) and, on success,
                    // re-sync so the earned row lands locally — same shape as the
                    // achievement-reward path.
                    is LevelReward.Cosmetic ->
                        if (cosmeticGrantApi.grantLevelCosmetic(reward.productId)) {
                            inventoryRepository.sync()
                        }
                }
            }
        }
        logger.d { "Granted ${rewards.size} reward(s) for level $level" }
    }
}

package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.ServerWitnessedAchievements
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.Wallet
import com.dangerfield.cards.server.domain.WalletRepository
import com.dangerfield.cards.server.http.ClientContext
import me.tatarka.inject.annotations.Inject
import org.slf4j.LoggerFactory
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Default [ServerWitnessedAchievements]: evaluates the count-based MP
 * achievements off [HandsFinishedRepository.countForUser] and grants on a
 * threshold crossing.
 *
 * A "grant" is the durable earned-achievement record
 * ([AchievementRepository.recordEarned] — what reaches the client's earned set
 * on its next achievements sync) plus, depending on the achievement's mapped
 * reward: an inventory earned-grant for a cosmetic
 * ([InventoryRepository.recordEarnedGrant]) and/or an idempotent chip grant to
 * the wallet ledger ([WalletRepository.apply]). Every reward step degrades
 * gracefully exactly like the client grant route: an id with no mapped reward
 * (or a cosmetic product missing from the catalog) still records the
 * achievement, it just grants no reward. Chip grants are keyed off a stable
 * per-achievement ledger key, so re-evaluating the same threshold on each
 * finished hand never double-credits.
 *
 * Cheap-by-skip: [evaluate] reads the earned set first and short-circuits when
 * every threshold id is already earned, so the hot path costs one read (not two
 * writes) once a user is past every threshold.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class DefaultServerWitnessedAchievements(
    private val handsFinished: HandsFinishedRepository,
    private val achievements: AchievementRepository,
    private val inventory: InventoryRepository,
    private val catalog: ProductCatalogSource,
    private val wallet: WalletRepository,
    private val clock: Clock,
) : ServerWitnessedAchievements {

    override suspend fun evaluate(userId: UserId) {
        val alreadyEarned = Catching { achievements.listEarned(userId) }
            .getOrNull()
            ?.mapTo(mutableSetOf()) { it.achievementId }
            ?: return
        val pending = COUNT_THRESHOLDS.filterKeys { it !in alreadyEarned }
        if (pending.isEmpty()) return

        val count = handsFinished.countForUser(userId)
        for ((achievementId, threshold) in pending) {
            if (count < threshold) continue
            Catching {
                achievements.recordEarned(userId, achievementId, clock.now())
                REWARD_PRODUCTS[achievementId]?.let { productId ->
                    catalog.readById(productId, SYSTEM_CONTEXT)?.let { product ->
                        inventory.recordEarnedGrant(userId, product.id, clock.now())
                    }
                }
                CHIP_REWARDS[achievementId]?.let { chips ->
                    wallet.apply(
                        userId = userId,
                        idempotencyKey = "achievement:$achievementId",
                        delta = chips,
                        reason = "achievement_grant:$achievementId",
                    )
                }
            }.onFailure {
                log.warn("server-witnessed grant failed for user {} achievement {}", userId.value, achievementId, it)
            }
        }
    }

    companion object {
        /**
         * Count-based server-witnessed MP achievements, keyed to the finished-
         * hand count that earns them. Per-hand-shape MP ids are intentionally
         * absent — they need richer signals than a raw count.
         */
        val COUNT_THRESHOLDS: Map<String, Long> = mapOf(
            "FIRST_HAND_MP" to 1L,
            "HANDS_100_MP" to 100L,
        )

        /**
         * Cosmetic each count-based achievement grants. Missing entries (or a
         * product absent from the catalog) simply skip the cosmetic — the
         * earned-achievement record still lands. Empty today: the only
         * count-based MP achievement that grants a reward (`HANDS_100_MP`)
         * now grants chips ([CHIP_REWARDS]) rather than a borrowed
         * single-player emote. Kept as the seam for any future count
         * achievement that maps to a real MP cosmetic.
         */
        val REWARD_PRODUCTS: Map<String, String> = emptyMap()

        /**
         * Chips each count-based achievement grants, applied idempotently to
         * the wallet ledger. `HANDS_100_MP` (100 finished MP hands) credits
         * [Wallet.ACHIEVEMENT_HANDS_100_GRANT].
         */
        val CHIP_REWARDS: Map<String, Long> = mapOf(
            "HANDS_100_MP" to Wallet.ACHIEVEMENT_HANDS_100_GRANT,
        )

        private val SYSTEM_CONTEXT = ClientContext(
            platform = ClientContext.Platform.Other,
            appVersion = null,
            buildNumber = null,
            preferredLocales = listOf("en"),
            countryCode = null,
        )

        private val log = LoggerFactory.getLogger(DefaultServerWitnessedAchievements::class.java)
    }
}

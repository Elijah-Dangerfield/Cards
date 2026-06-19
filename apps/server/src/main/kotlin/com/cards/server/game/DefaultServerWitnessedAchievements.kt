package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.ProductCatalogSource
import com.dangerfield.cards.server.domain.ServerWitnessedAchievements
import com.dangerfield.cards.server.domain.UserId
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
 * A "grant" is two idempotent writes: the durable earned-achievement record
 * ([AchievementRepository.recordEarned] — what reaches the client's earned set
 * on its next achievements sync) and, when the achievement maps to a cosmetic
 * that's present in the catalog, an inventory earned-grant
 * ([InventoryRepository.recordEarnedGrant]). The cosmetic step degrades
 * gracefully exactly like the client grant route: an id with no mapped product
 * (or a product missing from the catalog) still records the achievement, it
 * just grants no cosmetic.
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
         * earned-achievement record still lands. `HANDS_100_MP` reuses the
         * single-player grinder emote: a dedicated MP product is a content +
         * migration call the human owns, and the earned record is the
         * load-bearing half here.
         */
        val REWARD_PRODUCTS: Map<String, String> = mapOf(
            "HANDS_100_MP" to "emotes_grinder",
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

package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AchievementRepository
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.PlayerHandOutcome
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

    override suspend fun evaluateHand(userId: UserId, outcome: PlayerHandOutcome) {
        val alreadyEarned = Catching { achievements.listEarned(userId) }
            .getOrNull()
            ?.mapTo(mutableSetOf()) { it.achievementId }
            ?: return
        if (PER_HAND_IDS.all { it in alreadyEarned }) return

        val crossed = buildList {
            if (outcome.bustsDealt >= 1) add("FIRST_BUST_DEALT_MP")
            if (outcome.stackMultiple >= 3.0) add("TRIPLE_UP_MP")
            if (outcome.stackMultiple >= 2.0) add("DOUBLE_UP_MP")
            if (outcome.won && outcome.potTotal >= POT_5000_THRESHOLD) add("POT_5000_MP")
            // Cumulative ids tally the dedup'd ledger (this hand already
            // recorded). Only query when still unearned — the read is wasted
            // once the threshold has been crossed.
            if ("BUST_DEALT_5_MP" !in alreadyEarned &&
                handsFinished.bustsDealtForUser(userId) >= BUST_DEALT_5_THRESHOLD
            ) {
                add("BUST_DEALT_5_MP")
            }
            if ("WIN_BY_FOLD_10_MP" !in alreadyEarned &&
                handsFinished.winsByFoldForUser(userId) >= WIN_BY_FOLD_10_THRESHOLD
            ) {
                add("WIN_BY_FOLD_10_MP")
            }
        }
        for (achievementId in crossed) {
            if (achievementId in alreadyEarned) continue
            Catching {
                achievements.recordEarned(userId, achievementId, clock.now())
                CHIP_REWARDS[achievementId]?.let { chips ->
                    wallet.apply(
                        userId = userId,
                        idempotencyKey = "achievement:$achievementId",
                        delta = chips,
                        reason = "achievement_grant:$achievementId",
                    )
                }
            }.onFailure {
                log.warn("server-witnessed per-hand grant failed for user {} achievement {}", userId.value, achievementId, it)
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
         * Per-hand-shape server-witnessed MP achievements [evaluateHand]
         * drives. The one-shot ids (`FIRST_BUST_DEALT_MP`, double/triple-up,
         * pot-size) fire off a single hand's [PlayerHandOutcome]; the
         * cumulative ids (`BUST_DEALT_5_MP`, `WIN_BY_FOLD_10_MP`) fire off a
         * career tally over the finished-hand ledger
         * ([HandsFinishedRepository.bustsDealtForUser] /
         * [HandsFinishedRepository.winsByFoldForUser]). [evaluateHand]
         * short-circuits once every id here is earned.
         */
        val PER_HAND_IDS: Set<String> = setOf(
            "FIRST_BUST_DEALT_MP",
            "DOUBLE_UP_MP",
            "TRIPLE_UP_MP",
            "POT_5000_MP",
            "BUST_DEALT_5_MP",
            "WIN_BY_FOLD_10_MP",
        )

        /** Minimum total pot (chips) that earns `POT_5000_MP` for the winner. */
        const val POT_5000_THRESHOLD: Long = 5_000L

        /** Cumulative busts dealt that earns `BUST_DEALT_5_MP`. */
        const val BUST_DEALT_5_THRESHOLD: Long = 5L

        /** Cumulative pots won by fold that earns `WIN_BY_FOLD_10_MP`. */
        const val WIN_BY_FOLD_10_THRESHOLD: Long = 10L

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
            "FIRST_BUST_DEALT_MP" to Wallet.ACHIEVEMENT_FIRST_BUST_DEALT_MP_GRANT,
            "DOUBLE_UP_MP" to Wallet.ACHIEVEMENT_DOUBLE_UP_MP_GRANT,
            "TRIPLE_UP_MP" to Wallet.ACHIEVEMENT_TRIPLE_UP_MP_GRANT,
            "POT_5000_MP" to Wallet.ACHIEVEMENT_POT_5000_MP_GRANT,
            "BUST_DEALT_5_MP" to Wallet.ACHIEVEMENT_BUST_DEALT_5_MP_GRANT,
            "WIN_BY_FOLD_10_MP" to Wallet.ACHIEVEMENT_WIN_BY_FOLD_10_MP_GRANT,
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

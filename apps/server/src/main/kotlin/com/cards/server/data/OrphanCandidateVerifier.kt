package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.FoundingMemberCatalog
import com.dangerfield.cards.server.domain.InventoryRepository
import com.dangerfield.cards.server.domain.ProgressionRepository
import com.dangerfield.cards.server.domain.RoomService
import com.dangerfield.cards.server.domain.StarterInventory
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.WalletRepository
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The never-delete-progress guards shared by both orphan sweeps — the
 * "Hard guards" from `docs/wiki/account-lifecycle.md`. A candidate is only
 * deletable when ALL of these hold:
 *
 *  - **No real-money spend.** Any `iap.%` wallet event preserves the
 *    account — it is structurally off-limits regardless of activity.
 *  - **No engagement-grade inventory.** Any owned row that isn't in
 *    [StarterInventory.productIds] and isn't the founding-member badge
 *    was earned via gameplay or purchased with chips.
 *  - **At or below level 1.** Any meaningful XP (≥ [LEVEL_2_XP_THRESHOLD])
 *    is earned progress; the bias is to leak an orphan row rather than
 *    ever delete it. See `docs/decisions.md` 2026-06-19.
 *  - **No active room seat.** Deleting a seated player would tear their
 *    game out from under them, which is worse than the leaked profile.
 *
 * Returns the first guard that fired (for the sweep's log line), or null
 * when the candidate is safe to delete. The install sweep's SQL pre-filter
 * already excludes IAP spenders, so its wallet check here is a cheap
 * no-hit re-verification; the scheduled TTL sweep has no SQL gate and
 * relies on this class entirely.
 */
@SingleIn(ServerScope::class)
@Inject
class OrphanCandidateVerifier(
    private val wallets: WalletRepository,
    private val inventory: InventoryRepository,
    private val progression: ProgressionRepository,
    private val rooms: RoomService,
) {

    suspend fun skipReason(candidate: UserId): SkipReason? {
        if (wallets.hasIapSpend(candidate)) return SkipReason.IapSpend

        val owned = inventory.listOwned(candidate)
        val hasEngagementInventory = owned.any { row ->
            row.productId !in STARTER_AND_FOUNDING_IDS
        }
        if (hasEngagementInventory) return SkipReason.EngagementInventory

        // A missing progression row reads as 0 XP (level 1, deletable).
        val totalXp = progression.find(candidate)?.totalXp ?: 0L
        if (totalXp >= LEVEL_2_XP_THRESHOLD) return SkipReason.MeaningfulXp

        val isInRoom = rooms.snapshot().any { it.memberFor(candidate) != null }
        if (isInRoom) return SkipReason.ActiveRoomSeat

        return null
    }

    enum class SkipReason {
        IapSpend,
        EngagementInventory,
        MeaningfulXp,
        ActiveRoomSeat,
    }

    companion object {
        private val STARTER_AND_FOUNDING_IDS: Set<String> =
            StarterInventory.productIds.toSet() + FoundingMemberCatalog.PRODUCT_ID

        /**
         * Lifetime XP at which a player crosses from level 1 to level 2.
         * Mirrors the client level curve (`N² × 100`, so the level-1
         * ceiling is `1² × 100 = 100`; see `:libraries:cards` `Level.kt`).
         * Duplicated as a constant rather than depending on the client
         * module — the level math is a one-liner and the server stays
         * independent of the Compose/cards layer.
         */
        internal const val LEVEL_2_XP_THRESHOLD: Long = 100L
    }
}

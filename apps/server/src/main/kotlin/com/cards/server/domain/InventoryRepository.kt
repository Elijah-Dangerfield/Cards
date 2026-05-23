package com.dangerfield.cards.server.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Per-user owned-product ledger. One entry = "this user owns this
 * product." Idempotent on (userId, productId) — re-syncing the same
 * purchase from a different device is a no-op.
 *
 * Reconciliation model is intentionally simple for V1: the server takes
 * the client at its word about cost/timestamp and persists. When the
 * server-side chip ledger ships (out of V1 scope), this method gains a
 * second outcome — "couldn't afford it" — and the route maps to
 * SyncOutcome.Reverted with chipsToRefund. The current contract leaves
 * room: [recordPurchase] returns the resulting [OwnedItem] so the route
 * can echo it back.
 */
/**
 * How a row got into the inventory. Today: purchased (chips or IAP) or
 * earned (achievement / league reward — wired into the schema but not
 * yet called from anywhere). The visual "Earned" badge is gated on this
 * flag — see slice 4 in plan: foamy-tickling-meerkat.md.
 *
 * Wire form is the lowercase enum name (`purchased` / `earned`), matched
 * to the DB CHECK constraint in `V13__inventory_acquisition_source.sql`.
 */
enum class AcquisitionSource {
    Purchased,
    Earned;

    val wire: String get() = name.lowercase()

    companion object {
        fun fromWire(raw: String): AcquisitionSource =
            entries.firstOrNull { it.wire == raw }
                ?: error("Unknown acquisition_source: $raw")
    }
}

@OptIn(ExperimentalTime::class)
data class OwnedItem(
    val productId: String,
    val costChipsAtPurchase: Long,
    val purchasedAt: Instant,
    val acquisitionSource: AcquisitionSource = AcquisitionSource.Purchased,
)

@OptIn(ExperimentalTime::class)
interface InventoryRepository {

    /** Snapshot of everything this user owns. */
    suspend fun listOwned(userId: UserId): List<OwnedItem>

    /**
     * Idempotent upsert: if a row already exists for (userId, productId),
     * the existing row stays (first-purchase-wins for cost + timestamp).
     * Otherwise inserts. Returns the row that ends up persisted.
     *
     * Always writes `acquisition_source = 'purchased'`. The free-grant
     * path (achievement reward / league finish) goes through
     * [recordEarnedGrant] so the discriminator is right at write-time.
     */
    suspend fun recordPurchase(
        userId: UserId,
        productId: String,
        costChipsAtPurchase: Long,
        purchasedAt: Instant,
    ): OwnedItem

    /**
     * Idempotent grant of an item the user *earned* (achievement reward,
     * league finish, future free-grant paths). Returns the existing row
     * if the user already owns the product — first-grant-wins on cost
     * and timestamp, same as [recordPurchase]. No caller writes through
     * this method yet; landed as part of the data-model slice so the
     * achievement-reward inventory path has a single entry point when
     * it ships.
     */
    suspend fun recordEarnedGrant(
        userId: UserId,
        productId: String,
        grantedAt: Instant,
    ): OwnedItem

    /** Wipe everything for a user — used by the orphan sweep + future
     *  DELETE /v1/me extension when inventory becomes part of the
     *  account-delete cascade. */
    suspend fun deleteAllForUser(userId: UserId)
}

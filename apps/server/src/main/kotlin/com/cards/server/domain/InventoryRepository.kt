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
@OptIn(ExperimentalTime::class)
data class OwnedItem(
    val productId: String,
    val costChipsAtPurchase: Long,
    val purchasedAt: Instant,
)

@OptIn(ExperimentalTime::class)
interface InventoryRepository {

    /** Snapshot of everything this user owns. */
    suspend fun listOwned(userId: UserId): List<OwnedItem>

    /**
     * Idempotent upsert: if a row already exists for (userId, productId),
     * the existing row stays (first-purchase-wins for cost + timestamp).
     * Otherwise inserts. Returns the row that ends up persisted.
     */
    suspend fun recordPurchase(
        userId: UserId,
        productId: String,
        costChipsAtPurchase: Long,
        purchasedAt: Instant,
    ): OwnedItem

    /** Wipe everything for a user — used by the orphan sweep + future
     *  DELETE /v1/me extension when inventory becomes part of the
     *  account-delete cascade. */
    suspend fun deleteAllForUser(userId: UserId)
}

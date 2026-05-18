package com.dangerfield.cards.server.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Per-user equipped-product state. One entry = "this user has this product
 * equipped at this moment in time."
 *
 * The model is intentionally per-item rather than per-slot: the catalog
 * may grow categories the server doesn't know the names of (and shouldn't
 * have to). Rendering layers on the client decide which equipped item
 * "wins" in any given visible slot.
 *
 * Reconciliation is last-write-wins by [EquippedItem.updatedAt]. An
 * absent product id means "not equipped." Pending unequips reach us as
 * `unequip` ops on the sync route.
 */
@OptIn(ExperimentalTime::class)
data class EquippedItem(
    val productId: String,
    val updatedAt: Instant,
)

@OptIn(ExperimentalTime::class)
interface EquipmentRepository {

    /** Snapshot of every product this user currently has equipped. */
    suspend fun listEquipped(userId: UserId): List<EquippedItem>

    /**
     * Idempotent upsert: if the row exists with an [updatedAt] later than
     * [newUpdatedAt], the existing row wins and this call is a no-op.
     * Otherwise the row is created or refreshed.
     *
     * Returns the row that ended up in the table (either the existing
     * later one or the just-written one) so the caller can echo it back
     * to the client unchanged.
     */
    suspend fun equip(userId: UserId, productId: String, newUpdatedAt: Instant): EquippedItem

    /**
     * Idempotent delete: removes the row IF the existing row's
     * [updatedAt] is earlier than [opUpdatedAt]. If the existing row is
     * newer, the delete is rejected and the existing row is returned
     * so the caller can echo it back as authoritative.
     *
     * Returns null when the row no longer exists after the call
     * (successful delete or it was already absent).
     */
    suspend fun unequip(userId: UserId, productId: String, opUpdatedAt: Instant): EquippedItem?
}

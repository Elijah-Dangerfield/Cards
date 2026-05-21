package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * One row in the user's equipment book. Present-and-equipped means the
 * user has the product turned on; present-and-unequipped means they
 * explicitly turned it off (and we still owe the server that intent if
 * [syncState] is [EquipmentSyncState.Pending]); absent means they never
 * touched this product.
 *
 * The shop and the items screen render the equipped state by joining
 * inventory ∪ catalog ∪ equipment. Each surface decides which equipped
 * item "wins" if there's an implicit one-at-a-time rule (e.g. card back).
 */
data class EquipmentEntry(
    val productId: String,
    val isEquipped: Boolean,
    val syncState: EquipmentSyncState,
    /** Wall-clock when the user toggled. Drives server LWW reconciliation. */
    val updatedAtEpochMs: Long,
)

enum class EquipmentSyncState {
    /** Local toggle hasn't been confirmed by the server yet. */
    Pending,

    /** Server has accepted (or originated) the current value. */
    Synced,
}

/**
 * Owns the player's equipment intent + its sync state. Reads are flow-
 * based for live UI; writes are explicit suspend calls.
 *
 * Optimistic semantics, mirroring [InventoryRepository]:
 *  1. User taps Equip / Unequip → repo upserts a row with the new value
 *     and [EquipmentSyncState.Pending].
 *  2. Sync service runs (on launch / foreground) → posts pending rows
 *     to /v1/equipment/sync. Server reconciles + returns the
 *     authoritative set.
 *  3. Repo applies the snapshot: matching rows flip to Synced; rows the
 *     server doesn't return (= product not equipped server-side) flip
 *     to "is_equipped = false, sync_state = Synced" too — they'll be
 *     purged on the next cycle.
 *
 * The repository never enforces "one-per-category" rules. That's a
 * rendering-layer decision and would over-constrain the surface
 * (different screens may legitimately render different equipped items
 * for the same catalog entry).
 */
interface EquipmentRepository {

    fun observeEquipped(): Flow<List<EquipmentEntry>>

    /** All rows (including unequipped intents waiting to sync). */
    suspend fun getAll(): List<EquipmentEntry>

    /** Idempotent. Marks the row Pending and bumps `updated_at`. */
    suspend fun equip(productId: String): EquipmentToggleResult

    /** Idempotent. Marks the row Pending with `is_equipped = false`. */
    suspend fun unequip(productId: String): EquipmentToggleResult

    /**
     * Called by the sync service after a successful round-trip. The
     * [authoritative] set is whatever the server says is currently
     * equipped for this user. Anything missing from the set is unequipped
     * server-side; locally-Pending unequip rows for those products get
     * marked Synced (and eventually purged). Pending equip rows for
     * products NOT in the set stay Pending — we'll retry next cycle.
     */
    suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>)

    /**
     * Enforces "equipped ⇒ owned." Drops any equipment row whose
     * productId isn't in [ownedProductIds] — used by the inventory sync
     * service after it folds in the server-authoritative `owned` snapshot,
     * to repair drift between equipment and inventory (e.g. an item
     * refunded / revoked on another device, or a local cache that lost
     * its inventory rows but kept equipment).
     *
     * Returns the productIds that were dropped so the caller can log them
     * — drop-rate visibility is the only way to tell whether the drift is
     * a rare edge or a steady-state symptom of something else.
     */
    suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String>

    /** Called from "Fresh Start" / debug menus. */
    suspend fun deleteAll()
}

sealed interface EquipmentToggleResult {
    data object Success : EquipmentToggleResult
    /** Toggle was a no-op (already in the requested state, Synced). */
    data object NoChange : EquipmentToggleResult
}

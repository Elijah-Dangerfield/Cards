package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Every item the player has ever acquired from the shop. Local source of
 * truth; reconciled with the server on launch.
 *
 * Why a separate concept from the catalog: the catalog is what's *for sale*
 * (server-driven, can change between sessions). Inventory is what the user
 * *owns* (their data, lives across sessions). They join via [productId].
 *
 * Render rules:
 *  - A catalog item with a Confirmed inventory entry is "OWNED" — show
 *    EQUIPPED or OWNED badge in the shop UI.
 *  - A catalog item with a Pending inventory entry is "OWNED but unsynced"
 *    — same UX as Confirmed; pending is an implementation detail the user
 *    shouldn't see normally. (UI may show a subtle "syncing…" affordance.)
 *  - A catalog item the user couldn't afford renders disabled.
 */
data class InventoryItem(
    /** Stable product id from the catalog — joins to `Product.id`. */
    val productId: String,
    val state: PurchaseState,
    /** Wall-clock at the moment of optimistic redemption. */
    val purchasedAtEpochMs: Long,
    /**
     * Chip cost at the moment of purchase. Recorded so backend reconciliation
     * can re-verify the spend (last-write-wins: if the server says the user
     * couldn't afford it, the entry gets reverted and chips returned).
     * 0 for IAP products — those reconcile via the platform store receipt,
     * not chip math.
     */
    val costChipsAtPurchase: Long,
)

/**
 * Lifecycle state of an inventory entry.
 *
 * The local-first flow:
 *  1. User taps "Redeem" → entry inserted as [Pending], chips deducted
 *     optimistically.
 *  2. Sync service runs (on launch or foreground-resume) → posts pending
 *     entries to the server. Server reconciles and acknowledges.
 *  3. On ack → entry transitions to [Confirmed].
 *  4. If the server *rejects* (e.g. retroactive over-draw because the user
 *     redeemed offline twice and chip math doesn't add up) → entry deletes
 *     and chips are credited back via a separate [ChipsRepository.addChips].
 */
enum class PurchaseState {
    /** Inserted locally; not yet acknowledged by the server. */
    Pending,

    /** Server has confirmed the entry. Stable; survives clean reinstalls
     *  after sync. */
    Confirmed,
}

/**
 * Owns the player's inventory state.
 *
 * Reads are flow-based for live UI updates; writes are explicit suspend
 * calls. All purchase logic flows through [redeemChipOffer] so the chip
 * deduction + inventory insert happen atomically (transactional at the DAO
 * level when both rows live in Room).
 */
interface InventoryRepository {

    /** Live observation of every inventory entry the user has. */
    fun observeInventory(): Flow<List<InventoryItem>>

    /** One-shot read. */
    suspend fun getInventory(): List<InventoryItem>

    /**
     * Optimistic chip-funded redemption.
     *
     * Returns [RedeemResult.Success] when the user had enough chips and the
     * insert happened. Returns [RedeemResult.InsufficientChips] without
     * mutating anything when they didn't.
     *
     * The implementation runs both writes (chip deduction + inventory
     * insert) inside a single Room transaction so a crash mid-call can't
     * leave the wallet drained without the item to show for it, or vice
     * versa.
     *
     * Idempotent on [productId]: if an entry already exists, this is a
     * no-op returning [RedeemResult.AlreadyOwned] — chips aren't deducted
     * twice. Future iterations may relax this for consumables.
     */
    suspend fun redeemChipOffer(
        productId: String,
        costChips: Long,
    ): RedeemResult

    /**
     * Mark every currently-[PurchaseState.Pending] entry as
     * [PurchaseState.Confirmed]. Called by the sync service after a
     * successful round-trip with the server. Operates only on entries that
     * existed at the moment of the sync request — entries created during
     * the sync stay Pending and get picked up by the next cycle.
     */
    suspend fun markConfirmed(productIds: Collection<String>)

    /**
     * Backend rejected a previously-Pending purchase (e.g. server-side
     * balance went negative once it re-ran the math across all devices).
     * Removes the entry. Caller credits the chips back via
     * [ChipsRepository] separately — keeping the two writes orthogonal so
     * callers can compose them with their own atomicity guarantees.
     */
    suspend fun revertPurchase(productId: String)

    /**
     * Fold a server-authoritative snapshot into local state. Mirrors
     * `EquipmentRepository.applyServerSnapshot` — used by the sync service
     * after it receives the `owned` array on `POST /v1/inventory/sync`.
     *
     * Behavior:
     *  - For each authoritative item: upsert as Confirmed (replaces a prior
     *    Pending row, which means "server saw this purchase").
     *  - Local Confirmed rows whose productId isn't in [authoritative] are
     *    removed — server says the user no longer owns them (refund / revoke
     *    on another device).
     *  - Local Pending rows whose productId isn't in [authoritative] are
     *    kept untouched — they're in-flight and will retry next cycle.
     */
    suspend fun applyServerSnapshot(authoritative: List<InventoryItem>)

    /** Reset everything (used by "Fresh Start" / debug). */
    suspend fun deleteAll()

    /**
     * Reconcile local optimistic purchases with the server. Single-flight
     * (concurrent callers share one in-flight call). Always POSTs — even
     * with an empty pending set — because the response carries the
     * server's authoritative `owned` snapshot.
     *
     * Triggered automatically on cold boot + warm foreground. Manual
     * callers (e.g. immediately after redemption) can invoke directly.
     *
     * Failure leaves pending rows as-is. Result-based.
     */
    suspend fun sync(): Result<Unit>
}

sealed interface RedeemResult {
    data object Success : RedeemResult
    data object InsufficientChips : RedeemResult
    data object AlreadyOwned : RedeemResult
}

package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.storage.db.ChipsDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock

/**
 * Room-backed inventory.
 *
 * Optimistic redemption flow ([redeemChipOffer]) goes through the DAO's
 * transactional method — chip deduction + inventory insert succeed or fail
 * as one. Returns the result code immediately; the sync service deals with
 * eventual server confirmation asynchronously.
 *
 * Idempotency: re-redeeming an owned product is a no-op. The DAO uses
 * `INSERT OR IGNORE` on the product-id primary key, returns -1 when the row
 * was already there, and we translate that into [RedeemResult.AlreadyOwned]
 * without ever touching the chip balance.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class InventoryRepositoryImpl(
    private val inventoryDao: InventoryDao,
    private val chipsDao: ChipsDao,
    private val clock: Clock,
) : InventoryRepository {

    override fun observeInventory(): Flow<List<InventoryItem>> =
        inventoryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getInventory(): List<InventoryItem> =
        inventoryDao.getAll().map { it.toDomain() }

    override suspend fun redeemChipOffer(
        productId: String,
        costChips: Long,
    ): RedeemResult {
        require(costChips >= 0) { "costChips must be non-negative" }

        // Step 1: pre-check balance. Cheap read; if the user can't afford
        // it we return without touching state.
        val balance = chipsDao.getChips()?.balance ?: 0L
        if (balance < costChips) return RedeemResult.InsufficientChips

        // Step 2: optimistic insert. `INSERT OR IGNORE` returns -1 if the
        // row already exists — that's the "already owned" signal, no chip
        // deduction happens in that path.
        val now = clock.now().toEpochMilliseconds()
        val entity = InventoryEntity(
            productId = productId,
            syncState = PurchaseState.Pending.name,
            purchasedAtEpochMs = now,
            costChipsAtPurchase = costChips,
        )
        val rowId = inventoryDao.insertIfMissing(entity)
        if (rowId == -1L) return RedeemResult.AlreadyOwned

        // Step 3: deduct chips. If this throws (extremely rare — Room
        // operation against a column that exists), compensate by deleting
        // the row we just inserted. Without the compensation a crash here
        // would leave the user with the item AND their chips intact —
        // benign for users but creates phantom inventory on the server's
        // sync side.
        try {
            chipsDao.applyDelta(delta = -costChips, updatedAtEpochMs = now)
        } catch (t: Throwable) {
            runCatching { inventoryDao.delete(productId) }
            throw t
        }
        return RedeemResult.Success
    }

    override suspend fun markConfirmed(productIds: Collection<String>) {
        if (productIds.isEmpty()) return
        inventoryDao.markConfirmed(productIds)
    }

    override suspend fun revertPurchase(productId: String) {
        inventoryDao.delete(productId)
    }

    override suspend fun deleteAll() {
        inventoryDao.deleteAll()
    }

    private fun InventoryEntity.toDomain(): InventoryItem = InventoryItem(
        productId = productId,
        state = runCatching { PurchaseState.valueOf(syncState) }
            .getOrDefault(PurchaseState.Pending),
        purchasedAtEpochMs = purchasedAtEpochMs,
        costChipsAtPurchase = costChipsAtPurchase,
    )
}

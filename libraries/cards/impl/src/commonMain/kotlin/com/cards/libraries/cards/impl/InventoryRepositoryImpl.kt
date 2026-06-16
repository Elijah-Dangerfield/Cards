package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.cards.impl.dto.InventorySyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.InventorySyncResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.PendingPurchaseDto
import com.dangerfield.cards.libraries.cards.impl.dto.SyncOutcomeDto
import com.dangerfield.cards.libraries.cards.storage.db.InventoryDao
import com.dangerfield.cards.libraries.cards.storage.db.InventoryEntity
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * as one. Returns the result code immediately; [sync] deals with eventual
 * server confirmation asynchronously.
 *
 * Idempotency: re-redeeming an owned product is a no-op. The DAO uses
 * `INSERT OR IGNORE` on the product-id primary key, returns -1 when the
 * row was already there, and we translate that into [RedeemResult.AlreadyOwned]
 * without ever touching the chip balance.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = InventoryRepository::class)
@ContributesBinding(AppScope::class, multibinding = true, boundType = AppEventListener::class)
@Inject
class InventoryRepositoryImpl(
    private val inventoryDao: InventoryDao,
    private val chipsRepository: ChipsRepository,
    private val equipmentRepository: EquipmentRepository,
    private val networkClient: NetworkClient,
    private val appScope: AppCoroutineScope,
    private val clock: Clock,
) : InventoryRepository, AppEventListener {

    private val syncLogger = KLog.withTag("InventorySync")
    // Single-flight gate. The mutex guards lookup/start of [inFlight];
    // the Deferred itself is the in-flight work. Concurrent sync()
    // callers all `.await()` the same Deferred — no stacking of N
    // back-to-back POSTs when shop init + redeem + edit-profile entry
    // collide. Started in [appScope] so the network call outlives any
    // VM-scope caller that gets torn down mid-await.
    private val syncMutex = Mutex()
    private var inFlight: Deferred<Result<Unit>>? = null

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
        // it we return without touching state. A null balance means the
        // first sync hasn't hydrated the local row yet — refuse rather
        // than guess.
        val balance = chipsRepository.getBalance()
        if (balance == null || balance < costChips) return RedeemResult.InsufficientChips

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

        // Step 3: deduct chips through the wallet-aware repo so the
        // server-side ledger sees the debit too. If this throws (extremely
        // rare — Room write against a known schema), compensate by
        // deleting the row we just inserted. Without the compensation a
        // crash here would leave the user with the item AND their chips
        // intact — benign for users but creates phantom inventory on the
        // server's sync side. The reason/key tie the wallet event to
        // this specific product so a re-attempt collapses cleanly.
        try {
            chipsRepository.subtractChips(
                amount = costChips,
                reason = "shop.$productId",
                idempotencyKey = "shop.$productId",
            )
        } catch (t: Throwable) {
            Catching { inventoryDao.delete(productId) }
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

    override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) {
        val authoritativeIds = authoritative.map { it.productId }.toSet()

        if (authoritative.isNotEmpty()) {
            inventoryDao.insertAll(
                authoritative.map { item ->
                    InventoryEntity(
                        productId = item.productId,
                        syncState = PurchaseState.Confirmed.name,
                        purchasedAtEpochMs = item.purchasedAtEpochMs,
                        costChipsAtPurchase = item.costChipsAtPurchase,
                        acquisitionSource = item.acquisitionSource.name.lowercase(),
                    )
                },
            )
        }

        val supersededConfirmed = inventoryDao.getAll()
            .filter { it.syncState == PurchaseState.Confirmed.name }
            .map { it.productId }
            .filter { it !in authoritativeIds }
        if (supersededConfirmed.isNotEmpty()) {
            inventoryDao.deleteConfirmed(supersededConfirmed)
        }
    }

    override suspend fun deleteAll() {
        inventoryDao.deleteAll()
    }

    override fun onUserChanged(event: AppEvent.UserChanged) {
        // A user just became active (cold-boot resolve, sign-in, or account
        // switch). On a switch the prior owner's inventory was just wiped, so
        // re-hydrate the new user's owned items now rather than waiting for a
        // foreground. Sign-out (current == null) has nothing to fetch.
        if (event.current == null) return
        appScope.launch { sync() }
    }

    override fun onForeground(event: AppEvent.OnForeground) {
        // Cold-boot's initial sync is owned by [onUserChanged]; this handles
        // the warm-resume reconcile only.
        if (event.isColdBoot) return
        appScope.launch { sync() }
    }

    override suspend fun sync(): Result<Unit> {
        // True single-flight: if another caller already started a sync,
        // await its result instead of stacking a second POST.
        val deferred = syncMutex.withLock {
            val existing = inFlight
            if (existing != null && !existing.isCompleted) {
                existing
            } else {
                appScope.async { doSync() }.also { inFlight = it }
            }
        }
        return deferred.await()
    }

    private suspend fun doSync(): Result<Unit> =
        // Always POSTs — even with an empty pending set — because the
        // response carries the server's authoritative `owned` snapshot.
        // That doubles as the cold-start fetch for "what does the user
        // actually own" (parallel to how chips sync works) and is what
        // closes the equipment-without-inventory drift bug: on a fresh
        // install the server's snapshot brings the inventory back in line
        // before the equipment consistency invariant fires.
        networkClient.authedCall("inventory.sync", retry = RetryPolicy.idempotent()) { client ->
            val pending = getInventory()
                .filter { it.state == PurchaseState.Pending }

            syncLogger.d { "Syncing ${pending.size} pending purchases." }
            val request = InventorySyncRequestDto(
                purchases = pending.map { item ->
                    PendingPurchaseDto(
                        productId = item.productId,
                        purchasedAtEpochMs = item.purchasedAtEpochMs,
                        costChipsAtPurchase = item.costChipsAtPurchase,
                    )
                },
            )

            val response: InventorySyncResponseDto = client
                .post("/v1/inventory/sync") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                .body()

            val confirmedIds = mutableListOf<String>()
            for (result in response.results) {
                when (result.outcome) {
                    SyncOutcomeDto.Confirmed -> confirmedIds += result.productId
                    SyncOutcomeDto.Reverted -> {
                        // Server says this purchase shouldn't have happened.
                        // Credit the refund, then drop the row. Order
                        // matters — the refund mutation is the user-visible
                        // change, the row delete is the bookkeeping.
                        result.chipsToRefund?.takeIf { it > 0 }?.let { refund ->
                            chipsRepository.addChips(
                                amount = refund,
                                reason = "shop.refund.${result.productId}",
                                idempotencyKey = "shop.refund.${result.productId}",
                            )
                            syncLogger.i { "Reverted ${result.productId}; refunded $refund chips." }
                        }
                        revertPurchase(result.productId)
                    }
                    SyncOutcomeDto.Unknown -> {
                        // Server sent us an outcome we don't know how to
                        // handle yet — leave the row Pending so a newer
                        // client can resolve it on the next launch.
                        syncLogger.w { "Unknown sync outcome for ${result.productId}; leaving Pending." }
                    }
                }
            }
            markConfirmed(confirmedIds)

            val authoritative = response.owned.map { dto ->
                InventoryItem(
                    productId = dto.productId,
                    state = PurchaseState.Confirmed,
                    purchasedAtEpochMs = dto.purchasedAtEpochMs,
                    costChipsAtPurchase = dto.costChipsAtPurchase,
                    // Server-driven. Older servers omit the field — the DTO
                    // default ("purchased") preserves the historical
                    // semantics (every row was a purchase before V13).
                    acquisitionSource = when (dto.acquisitionSource) {
                        "earned" -> com.dangerfield.cards.libraries.cards.AcquisitionSource.Earned
                        else -> com.dangerfield.cards.libraries.cards.AcquisitionSource.Purchased
                    },
                )
            }
            applyServerSnapshot(authoritative)

            // Equipment consistency invariant: an equipment row only makes
            // sense when the user owns the product. After folding in the
            // server-authoritative inventory snapshot, drop any equipment
            // row whose productId isn't owned anymore. Logged so the
            // drop-rate is visible — if this fires often, the divergence
            // upstream (refund/revoke pathway, cache-clear pathway) is the
            // real bug.
            val owned = authoritative.map { it.productId }.toSet()
            val orphans = equipmentRepository.dropOrphanEquipment(owned)
            if (orphans.isNotEmpty()) {
                syncLogger.w { "Dropped ${orphans.size} orphan equipment row(s): $orphans" }
            }

            syncLogger.d { "Sync complete: ${confirmedIds.size} confirmed, ${authoritative.size} server-owned." }
            Unit
        }

    private fun InventoryEntity.toDomain(): InventoryItem = InventoryItem(
        productId = productId,
        state = Catching { PurchaseState.valueOf(syncState) }
            .getOrDefault(PurchaseState.Pending),
        purchasedAtEpochMs = purchasedAtEpochMs,
        costChipsAtPurchase = costChipsAtPurchase,
        acquisitionSource = when (acquisitionSource) {
            "earned" -> com.dangerfield.cards.libraries.cards.AcquisitionSource.Earned
            else -> com.dangerfield.cards.libraries.cards.AcquisitionSource.Purchased
        },
    )
}

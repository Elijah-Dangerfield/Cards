package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.InventorySyncService
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.impl.dto.InventorySyncRequestDto
import com.dangerfield.cards.libraries.cards.impl.dto.InventorySyncResponseDto
import com.dangerfield.cards.libraries.cards.impl.dto.PendingPurchaseDto
import com.dangerfield.cards.libraries.cards.impl.dto.SyncOutcomeDto
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class InventorySyncServiceImpl(
    private val inventoryRepository: InventoryRepository,
    private val chipsRepository: ChipsRepository,
    private val equipmentRepository: EquipmentRepository,
    private val networkClient: NetworkClient,
) : InventorySyncService {

    private val logger = KLog.withTag("InventorySync")
    private val mutex = Mutex()

    override suspend fun sync(): Result<Unit> = mutex.withLock {
        // Always POSTs — even with an empty pending set — because the
        // response carries the server's authoritative `owned` snapshot.
        // That doubles as the cold-start fetch for "what does the user
        // actually own" (parallel to how `EquipmentSyncServiceImpl` works)
        // and is what closes the equipment-without-inventory drift bug:
        // on a fresh install the server's snapshot brings the inventory
        // back in line before the equipment consistency invariant fires.
        Catching {
            val pending = inventoryRepository.getInventory()
                .filter { it.state == PurchaseState.Pending }

            logger.d { "Syncing ${pending.size} pending purchases." }
            val request = InventorySyncRequestDto(
                purchases = pending.map { item ->
                    PendingPurchaseDto(
                        productId = item.productId,
                        purchasedAtEpochMs = item.purchasedAtEpochMs,
                        costChipsAtPurchase = item.costChipsAtPurchase,
                    )
                },
            )

            val response: InventorySyncResponseDto = networkClient.authenticatedClient
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
                            chipsRepository.applyDelta(
                                delta = refund,
                                reason = "shop.refund.${result.productId}",
                                idempotencyKey = "shop.refund.${result.productId}",
                            )
                            logger.i { "Reverted ${result.productId}; refunded $refund chips." }
                        }
                        inventoryRepository.revertPurchase(result.productId)
                    }
                    SyncOutcomeDto.Unknown -> {
                        // Server sent us an outcome we don't know how to
                        // handle yet — leave the row Pending so a newer
                        // client can resolve it on the next launch.
                        logger.w { "Unknown sync outcome for ${result.productId}; leaving Pending." }
                    }
                }
            }
            inventoryRepository.markConfirmed(confirmedIds)

            val authoritative = response.owned.map { dto ->
                InventoryItem(
                    productId = dto.productId,
                    state = PurchaseState.Confirmed,
                    purchasedAtEpochMs = dto.purchasedAtEpochMs,
                    costChipsAtPurchase = dto.costChipsAtPurchase,
                )
            }
            inventoryRepository.applyServerSnapshot(authoritative)

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
                logger.w { "Dropped ${orphans.size} orphan equipment row(s): $orphans" }
            }

            logger.d { "Sync complete: ${confirmedIds.size} confirmed, ${authoritative.size} server-owned." }
            Unit
        }.onFailure { logger.w(it) { "Inventory sync failed; pending rows stay Pending." } }
    }
}

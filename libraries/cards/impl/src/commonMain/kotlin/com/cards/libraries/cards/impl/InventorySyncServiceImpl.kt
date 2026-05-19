package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
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
    private val networkClient: NetworkClient,
) : InventorySyncService {

    private val logger = KLog.withTag("InventorySync")
    private val mutex = Mutex()

    override suspend fun sync(): Result<Unit> = mutex.withLock {
        // TODO(shop-roadmap §2): post-auth this becomes the server-
        //   authoritative reconciliation pass — server can revert rows
        //   for race / insufficient_chips / offer_expired / revoked
        //   reasons and we re-credit chips with a soft toast. Add
        //   telemetry events (sync.confirmed / sync.reverted{reason} /
        //   sync.failed / sync.empty_inventory). See docs/shop-roadmap.md.
        Catching {
            val pending = inventoryRepository.getInventory()
                .filter { it.state == PurchaseState.Pending }

            if (pending.isEmpty()) {
                logger.d { "No pending purchases — sync no-op." }
                return@Catching
            }

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

            val response: InventorySyncResponseDto = networkClient.client
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
            logger.d { "Sync complete: ${confirmedIds.size} confirmed." }
        }.onFailure { logger.w(it) { "Inventory sync failed; pending rows stay Pending." } }
    }
}

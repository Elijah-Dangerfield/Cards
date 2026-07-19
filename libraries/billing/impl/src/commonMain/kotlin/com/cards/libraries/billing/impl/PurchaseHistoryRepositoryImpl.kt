package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.PurchaseHistoryItem
import com.dangerfield.cards.libraries.billing.PurchaseHistoryOutcome
import com.dangerfield.cards.libraries.billing.PurchaseHistoryRepository
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseStatus
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.RealPurchasesEnabled
import com.dangerfield.cards.libraries.billing.StoreKitCoordinator
import com.dangerfield.cards.libraries.billing.awaitUnfinishedTransactions
import com.dangerfield.cards.libraries.billing.toPurchaseResult
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.networking.authedCall
import com.dangerfield.cards.libraries.networking.retry.RetryPolicy
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductsRepository
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * HTTP impl of [PurchaseHistoryRepository]. Two sources, merged:
 *
 *  - The server's disposition log (`GET /v1/billing/history`) — the
 *    authoritative status of every purchase the server has seen.
 *  - The store's own **unfinished** transactions (StoreKit
 *    `Transaction.unfinished`) — purchases the user has paid for that the server
 *    hasn't confirmed yet (a transient redeem failure, or the server unreachable
 *    right after payment). These are shown as [PurchaseStatus.Pending] so a
 *    paid-but-uncredited purchase is visible immediately instead of looking like
 *    the money vanished — even when the server itself can't be reached.
 *
 * A transaction the server already knows about wins (its status is
 * authoritative); a local unfinished one is only added if the server has no row
 * for it. Each row is enriched with the pack's display details from the local
 * catalog; a delisted pack falls back to a generic label.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class PurchaseHistoryRepositoryImpl(
    private val networkClient: NetworkClient,
    private val productsRepository: ProductsRepository,
    private val storeKitCoordinator: StoreKitCoordinator,
    private val realPurchasesEnabled: RealPurchasesEnabled,
) : PurchaseHistoryRepository {

    private val logger = KLog.withTag("PurchaseHistoryRepository")

    override suspend fun history(): PurchaseHistoryOutcome {
        val packs = Catching { productsRepository.observeCatalog().first() }
            .getOrNull()?.chipPacks.orEmpty()

        val serverOutcome = fetchServerHistory(packs)
        val localPending = localPendingItems(packs)

        return when (serverOutcome) {
            is PurchaseHistoryOutcome.Loaded -> {
                val known = serverOutcome.items.map { it.transactionId }.toSet()
                val merged = (serverOutcome.items + localPending.filter { it.transactionId !in known })
                    .sortedByDescending { it.dateEpochMs }
                PurchaseHistoryOutcome.Loaded(merged)
            }
            // Server unreachable: still show the in-flight purchase so the user
            // isn't left wondering where their money went. A retry gets the rest.
            PurchaseHistoryOutcome.Unavailable ->
                if (localPending.isEmpty()) {
                    PurchaseHistoryOutcome.Unavailable
                } else {
                    PurchaseHistoryOutcome.Loaded(localPending.sortedByDescending { it.dateEpochMs })
                }
        }
    }

    private suspend fun fetchServerHistory(packs: List<Product.ChipPack>): PurchaseHistoryOutcome {
        val byId = packs.associateBy { it.id }
        val result: Catching<PurchaseHistoryOutcome> = networkClient.authedCall(
            description = "billing.history",
            retry = RetryPolicy.idempotent(),
        ) { client ->
            val body: HistoryResponseDto = client.get("/v1/billing/history").body()
            PurchaseHistoryOutcome.Loaded(body.items.map { it.toItem(byId) })
        }
        return result.getOrElse { error ->
            logger.w(error) { "purchase history fetch failed" }
            PurchaseHistoryOutcome.Unavailable
        }
    }

    private suspend fun localPendingItems(packs: List<Product.ChipPack>): List<PurchaseHistoryItem> {
        if (!realPurchasesEnabled()) return emptyList()
        val bySku = packs.associateBy { it.store.sku }
        return Catching { storeKitCoordinator.awaitUnfinishedTransactions() }
            .getOrNull().orEmpty()
            .mapNotNull { it.toPurchaseResult().transactionOrNull() }
            .map { transaction -> transaction.toPendingItem(bySku[transaction.sku]) }
    }

    private fun PurchaseResult.transactionOrNull(): PurchaseTransaction? = when (this) {
        is PurchaseResult.Success -> transaction
        is PurchaseResult.AlreadyOwned -> transaction
        else -> null
    }

    private fun PurchaseTransaction.toPendingItem(pack: Product.ChipPack?): PurchaseHistoryItem =
        PurchaseHistoryItem(
            transactionId = orderId,
            productId = pack?.id ?: sku,
            title = pack?.title ?: "Chip pack",
            iconEmoji = pack?.iconEmoji ?: "🪙",
            chips = pack?.grantsChips ?: 0L,
            status = PurchaseStatus.Pending,
            dateEpochMs = purchasedAtEpochMs,
        )

    private fun HistoryItemDto.toItem(packs: Map<String, Product.ChipPack>): PurchaseHistoryItem {
        val pack = packs[productId]
        return PurchaseHistoryItem(
            transactionId = transactionId,
            productId = productId,
            title = pack?.title ?: "Chip pack",
            iconEmoji = pack?.iconEmoji ?: "🪙",
            chips = pack?.grantsChips ?: 0L,
            status = PurchaseStatus.fromWire(status),
            dateEpochMs = dateEpochMs,
        )
    }
}

@Serializable
private data class HistoryResponseDto(val items: List<HistoryItemDto>)

@Serializable
private data class HistoryItemDto(
    val store: String,
    val transactionId: String,
    val productId: String,
    val status: String,
    val dateEpochMs: Long,
)

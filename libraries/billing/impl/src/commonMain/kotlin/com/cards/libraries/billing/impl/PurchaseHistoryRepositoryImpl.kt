package com.dangerfield.cards.libraries.billing.impl

import com.dangerfield.cards.libraries.billing.PurchaseHistoryItem
import com.dangerfield.cards.libraries.billing.PurchaseHistoryOutcome
import com.dangerfield.cards.libraries.billing.PurchaseHistoryRepository
import com.dangerfield.cards.libraries.billing.PurchaseStatus
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
 * HTTP impl of [PurchaseHistoryRepository]. Fetches the server's minimal history
 * rows and joins each to its catalog [Product.ChipPack] for the display title,
 * icon, and chip amount. A delisted pack falls back to a generic label so an old
 * purchase still renders instead of vanishing.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class PurchaseHistoryRepositoryImpl(
    private val networkClient: NetworkClient,
    private val productsRepository: ProductsRepository,
) : PurchaseHistoryRepository {

    private val logger = KLog.withTag("PurchaseHistoryRepository")

    override suspend fun history(): PurchaseHistoryOutcome {
        val packs = Catching { productsRepository.observeCatalog().first() }
            .getOrNull()?.chipPacks.orEmpty()
            .associateBy { it.id }

        val result: Catching<PurchaseHistoryOutcome> = networkClient.authedCall(
            description = "billing.history",
            retry = RetryPolicy.idempotent(),
        ) { client ->
            val body: HistoryResponseDto = client.get("/v1/billing/history").body()
            PurchaseHistoryOutcome.Loaded(body.items.map { it.toItem(packs) })
        }
        return result.getOrElse { error ->
            logger.w(error) { "purchase history fetch failed" }
            PurchaseHistoryOutcome.Unavailable
        }
    }

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

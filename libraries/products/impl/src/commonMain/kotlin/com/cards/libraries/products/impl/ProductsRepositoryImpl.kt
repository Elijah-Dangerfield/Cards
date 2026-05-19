package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.billing.BillingAvailability
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.products.StoreSku
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * In-memory products repository. Holds the most recent successful catalog in
 * a [StateFlow] that the UI subscribes to; [refresh] fetches and updates.
 *
 * Concurrency: a [Mutex] serializes refreshes so two simultaneous callers
 * (screen init + pull-to-refresh, say) share one in-flight network call.
 * Both get the same result. The mutex is only held while the network is
 * working — observers reading the flow are never blocked.
 *
 * Error handling: [refresh] returns a [Result] so the caller decides what to
 * surface. Errors don't poison the cache — a failed refresh leaves the prior
 * successful catalog intact, so a flaky network doesn't blow away the user's
 * view of the shop.
 *
 * Time anchor: every successful refresh also captures a [CatalogTimeAnchor]
 * pairing the server's wall clock with the device's monotonic clock at
 * fetch time. UI subscribes to [observeTimeAnchor] for clock-spoof-
 * resistant countdowns on sale-window offers.
 *
 * **Store reconciliation:** after fetching the catalog
 * we ask [BillingAvailability] which IAP SKUs the platform store actually
 * recognizes. Packs whose SKU isn't in the store response are dropped —
 * server config can be ahead of the store (staging, unrolled releases,
 * region restrictions) and we'd rather hide a pack than show one that
 * fails at purchase time. SKUs the store knows about have their
 * [StoreSku.fallbackPriceDisplay] overwritten with the store's localized
 * price string.
 *
 * The reconciliation is best-effort: a failed store query leaves the
 * catalog unchanged (fallback prices visible) rather than dropping every
 * IAP pack. With [com.dangerfield.cards.libraries.billing.impl.NoOpBillingClient]
 * bound (the default until store credentials are provisioned), the store
 * returns an empty product map and every IAP pack is hidden — which is
 * the desired pre-launch state.
 *
 * No on-disk caching in V1 — the catalog is small, the API is cheap, and a
 * cold launch with no network just shows the empty state. Adding offline
 * persistence is straightforward via [:libraries:storage] when needed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ProductsRepositoryImpl(
    private val dataSource: ProductsHttpDataSource,
    private val billingAvailability: BillingAvailability,
) : ProductsRepository {

    private val logger = KLog.withTag("ProductsRepository")
    private val state = MutableStateFlow(ProductCatalog.Empty)
    private val timeAnchor = MutableStateFlow<CatalogTimeAnchor?>(null)
    private val refreshMutex = Mutex()

    override fun observeCatalog(): StateFlow<ProductCatalog> = state.asStateFlow()

    override fun observeTimeAnchor(): Flow<CatalogTimeAnchor?> = timeAnchor.asStateFlow()

    override suspend fun refresh(): Result<ProductCatalog> = refreshMutex.withLock {
        Catching {
            val dto = dataSource.fetchCatalog()
            val rawCatalog = dto.toDomain()

            val storeProducts = queryStoreFor(rawCatalog)
            val reconciled = rawCatalog.reconcileAgainst(storeProducts)

            // Capture the anchor BEFORE updating the catalog flow so any
            // synchronous UI subscriber sees both updates in a consistent
            // order. Anchor uses serverNowEpochMs from the response paired
            // with TimeSource.Monotonic.markNow() inside `capture()`.
            timeAnchor.value = CatalogTimeAnchor.capture(serverNowEpochMs = dto.serverNowEpochMs)
            state.value = reconciled
            reconciled
        }
    }

    /**
     * Best-effort store query. Returns an empty map when the store call
     * fails OR when there are no IAP packs in the catalog. Failures are
     * logged but don't propagate — the catalog gets the fallback price
     * treatment instead.
     */
    private suspend fun queryStoreFor(catalog: ProductCatalog): Map<String, BillingProduct> {
        val skus = catalog.chipPacks.map { it.store.sku }.toSet()
        if (skus.isEmpty()) return emptyMap()
        return when (val result = billingAvailability.refresh(skus)) {
            is QueryProductsResult.Success -> result.products
            is QueryProductsResult.NotConnected -> {
                logger.w { "Store not connected during catalog refresh; using cached snapshot" }
                billingAvailability.snapshot()
            }
            is QueryProductsResult.Failed -> {
                logger.w { "Store query failed during catalog refresh (${result.message}); using cached snapshot" }
                billingAvailability.snapshot()
            }
        }
    }
}

/**
 * Drop ChipPacks whose SKU the platform store doesn't recognize and
 * overlay the store's localized price on the survivors. Pure function
 * for unit-testability — the repo just glues it onto the refresh path.
 *
 * When [storeProducts] is empty AND [com.dangerfield.cards.libraries.billing.impl.NoOpBillingClient]
 * is bound (its `queryProducts` always returns Success(emptyMap)), every
 * IAP pack is filtered out. That's the "store listings not provisioned"
 * baseline and it's intentional — we'd rather hide IAP than show
 * un-buyable products. A real billing impl that reports
 * [QueryProductsResult.NotConnected] / [QueryProductsResult.Failed]
 * leaves the cached snapshot in place instead.
 */
internal fun ProductCatalog.reconcileAgainst(
    storeProducts: Map<String, BillingProduct>,
): ProductCatalog {
    val reconciledPacks = chipPacks.mapNotNull { pack ->
        val storeProduct = storeProducts[pack.store.sku] ?: return@mapNotNull null
        if (storeProduct.displayPrice == pack.store.fallbackPriceDisplay) {
            pack
        } else {
            pack.copy(
                store = pack.store.copy(fallbackPriceDisplay = storeProduct.displayPrice),
            )
        }
    }
    return copy(chipPacks = reconciledPacks)
}

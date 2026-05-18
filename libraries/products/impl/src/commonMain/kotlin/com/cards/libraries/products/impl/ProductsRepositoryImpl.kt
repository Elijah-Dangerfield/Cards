package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
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
 * No on-disk caching in V1 — the catalog is small, the API is cheap, and a
 * cold launch with no network just shows the empty state. Adding offline
 * persistence is straightforward via [:libraries:storage] when needed.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ProductsRepositoryImpl(
    private val dataSource: ProductsHttpDataSource,
) : ProductsRepository {

    private val state = MutableStateFlow(ProductCatalog.Empty)
    private val timeAnchor = MutableStateFlow<CatalogTimeAnchor?>(null)
    private val refreshMutex = Mutex()

    override fun observeCatalog(): StateFlow<ProductCatalog> = state.asStateFlow()

    override fun observeTimeAnchor(): Flow<CatalogTimeAnchor?> = timeAnchor.asStateFlow()

    override suspend fun refresh(): Result<ProductCatalog> = refreshMutex.withLock {
        Catching {
            val dto = dataSource.fetchCatalog()
            val catalog = dto.toDomain()
            // Capture the anchor BEFORE updating the catalog flow so any
            // synchronous UI subscriber sees both updates in a consistent
            // order. Anchor uses serverNowEpochMs from the response paired
            // with TimeSource.Monotonic.markNow() inside `capture()`.
            timeAnchor.value = CatalogTimeAnchor.capture(serverNowEpochMs = dto.serverNowEpochMs)
            state.value = catalog
            catalog
        }
    }
}

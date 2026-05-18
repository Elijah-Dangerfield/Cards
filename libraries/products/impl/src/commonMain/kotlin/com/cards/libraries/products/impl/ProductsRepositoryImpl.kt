package com.dangerfield.cards.libraries.products.impl

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
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
    private val refreshMutex = Mutex()

    override fun observeCatalog(): StateFlow<ProductCatalog> = state.asStateFlow()

    override suspend fun refresh(): Result<ProductCatalog> = refreshMutex.withLock {
        Catching {
            val catalog = dataSource.fetchCatalog().toDomain()
            state.value = catalog
            catalog
        }
    }
}

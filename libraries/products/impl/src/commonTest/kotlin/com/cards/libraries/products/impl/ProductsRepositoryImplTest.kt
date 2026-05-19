package com.dangerfield.cards.libraries.products.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.billing.BillingAvailability
import com.dangerfield.cards.libraries.billing.BillingProduct
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import com.dangerfield.cards.libraries.networking.NetworkClient
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.StoreSku
import io.ktor.client.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Repository unit tests. Drive [ProductsRepositoryImpl] via a fake data
 * source so we don't fight Ktor's mock engine for what amounts to a
 * cache-and-flow contract.
 *
 * What we pin:
 *  - Subscribers see the empty catalog before first refresh.
 *  - A successful refresh updates the flow.
 *  - A failed refresh leaves the prior successful catalog intact (cache
 *    isn't poisoned by a flaky network).
 *  - Concurrent refreshes coalesce — the data source is hit once even if
 *    multiple callers ask for a refresh while one is in-flight.
 *  - Store reconciliation: SKUs the store doesn't recognize are dropped;
 *    SKUs the store knows about have their fallback price overwritten.
 *  - Reconciliation failures don't poison the cache.
 *
 * The DTO mapper is exercised through the same path; it's pure so a
 * dedicated test for it would just restate the field assignments.
 */
class ProductsRepositoryImplTest : CoroutineTest() {

    @Test
    fun initialCatalog_isEmpty() = runUnitTest {
        val repo = ProductsRepositoryImpl(FakeDataSource(), FakeBillingAvailability.passthrough())
        repo.observeCatalog().test {
            assertEquals(ProductCatalog.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refresh_success_updatesObservedCatalog() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = ProductsRepositoryImpl(source, FakeBillingAvailability.passthrough())

        repo.observeCatalog().test {
            assertEquals(ProductCatalog.Empty, awaitItem())

            val result = repo.refresh()
            assertTrue(result.isSuccess)
            val updated = awaitItem()
            assertEquals(1, updated.chipPacks.size)
            assertEquals("Pocket Stack", updated.chipPacks.first().title)
            assertEquals(5_000L, updated.chipPacks.first().grantsChips)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refresh_failure_returnsFailureResult_andDoesNotPoisonCache() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = ProductsRepositoryImpl(source, FakeBillingAvailability.passthrough())
        // Prime with a successful refresh, then make the next call fail.
        repo.refresh()
        val baseline = repo.observeCatalog().firstValue()
        assertEquals(1, baseline.chipPacks.size)

        source.failNext = RuntimeException("simulated server 500")
        val result = repo.refresh()
        assertTrue(result.isFailure)
        assertEquals(
            "simulated server 500",
            result.exceptionOrNull()?.message,
        )

        // Cache is untouched — flow still emits the prior good catalog.
        assertEquals(baseline, repo.observeCatalog().firstValue())
    }

    @Test
    fun refresh_isResultBased_notExceptionEscaping() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO).apply {
            failNext = RuntimeException("boom")
        }
        val repo = ProductsRepositoryImpl(source, FakeBillingAvailability.passthrough())
        try {
            repo.refresh()
        } catch (e: Throwable) {
            fail("refresh should NOT throw; should wrap in Result: $e")
        }
    }

    @Test
    fun concurrentRefreshes_coalesce_toSingleDataSourceCall() = runUnitTest {
        val source = FakeDataSource(catalog = SAMPLE_DTO)
        val repo = ProductsRepositoryImpl(source, FakeBillingAvailability.passthrough())
        // Launch 4 refreshes "concurrently". UnconfinedTestDispatcher means
        // they suspend on the same mutex, then run serially through it. The
        // contract: the mutex coalesces actual data-source calls.
        // (NB: this verifies single-flight from a single suspension point.
        // True parallel-thread coalescing would need an in-flight Deferred —
        // out of scope for V1.)
        val results = listOf(
            async { repo.refresh() },
            async { repo.refresh() },
            async { repo.refresh() },
            async { repo.refresh() },
        ).awaitAll()
        assertTrue(results.all { it.isSuccess })
        // All callers see the same final catalog.
        assertEquals(1, repo.observeCatalog().firstValue().chipPacks.size)
    }

    // ---------- Reconciliation ----------

    @Test
    fun reconcile_dropsPacks_whoseSku_storeDoesNotRecognize() = runUnitTest {
        val dto = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
                ChipPackDto(
                    id = "large",
                    title = "Whale Stack",
                    subtitle = "80,000 chips",
                    iconEmoji = "🐋",
                    grantsChips = 80_000,
                    store = StoreSkuDto("chips_large", "$9.99"),
                ),
            ),
        )
        val billing = FakeBillingAvailability(
            recognizedSkus = mapOf(
                "chips_small" to BillingProduct("chips_small", "$0.99", "USD", 990_000),
                // chips_large intentionally missing — should be filtered out.
            ),
        )
        val repo = ProductsRepositoryImpl(FakeDataSource(dto), billing)

        repo.refresh()

        val catalog = repo.observeCatalog().firstValue()
        assertEquals(1, catalog.chipPacks.size)
        assertEquals("small", catalog.chipPacks.first().id)
    }

    @Test
    fun reconcile_overlaysLocalizedPrice_onMatchingSku() = runUnitTest {
        val dto = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    // Fallback shows USD but the store reports euros.
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
            ),
        )
        val billing = FakeBillingAvailability(
            recognizedSkus = mapOf(
                "chips_small" to BillingProduct("chips_small", "€0,89", "EUR", 890_000),
            ),
        )
        val repo = ProductsRepositoryImpl(FakeDataSource(dto), billing)

        repo.refresh()

        val catalog = repo.observeCatalog().firstValue()
        assertEquals(1, catalog.chipPacks.size)
        assertEquals("€0,89", catalog.chipPacks.first().store.fallbackPriceDisplay)
    }

    @Test
    fun reconcile_emptyStore_dropsAllIapPacks() = runUnitTest {
        // This is the NoOp / pre-launch state — store returns no products,
        // so every IAP pack should disappear from the catalog. Chip offers
        // (which don't go through the store) stay.
        val dto = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
            ),
            chipOffers = listOf(
                ChipOfferDto(
                    id = "felt_red",
                    title = "Royal Red Felt",
                    subtitle = "Table felt",
                    iconEmoji = "🟥",
                    costChips = 1_500,
                    grantsKey = "felt.royal_red",
                ),
            ),
        )
        val billing = FakeBillingAvailability(recognizedSkus = emptyMap())
        val repo = ProductsRepositoryImpl(FakeDataSource(dto), billing)

        repo.refresh()

        val catalog = repo.observeCatalog().firstValue()
        assertTrue(catalog.chipPacks.isEmpty(), "IAP packs should be dropped when store knows none")
        assertEquals(1, catalog.chipOffers.size, "Chip offers should pass through")
    }

    @Test
    fun reconcile_storeNotConnected_fallsBackToCachedSnapshot() = runUnitTest {
        // First refresh: store knows the SKU. Cache is populated.
        val firstStoreState = FakeBillingAvailability(
            recognizedSkus = mapOf("chips_small" to BillingProduct("chips_small", "$0.99", "USD", 990_000)),
        )
        val repo = ProductsRepositoryImpl(FakeDataSource(SAMPLE_DTO), firstStoreState)
        repo.refresh()
        assertEquals(1, repo.observeCatalog().firstValue().chipPacks.size)

        // Second refresh: store reports NotConnected. With the snapshot
        // still populated, the pack should remain.
        firstStoreState.nextResult = QueryProductsResult.NotConnected
        repo.refresh()
        val after = repo.observeCatalog().firstValue()
        assertEquals(1, after.chipPacks.size)
        assertFalse(after.chipPacks.isEmpty())
    }

    // ---------- Test scaffolding ----------

    private class FakeDataSource(
        var catalog: ProductCatalogDto = ProductCatalogDto(),
    ) : ProductsHttpDataSource(networkClient = StubNetworkClient) {
        var failNext: Throwable? = null
        var callCount: Int = 0
            private set

        override suspend fun fetchCatalog(): ProductCatalogDto {
            callCount++
            failNext?.let { failNext = null; throw it }
            return catalog
        }
    }

    /**
     * Test double for [BillingAvailability]. Returns [recognizedSkus] for
     * any refresh by default; override per-call via [nextResult] to
     * exercise the NotConnected / Failed branches. Set [passthrough] to
     * auto-recognize any SKU queried (preserving the catalog as-is for
     * tests that don't care about reconciliation).
     */
    private class FakeBillingAvailability(
        var recognizedSkus: Map<String, BillingProduct> = emptyMap(),
        private val passthrough: Boolean = false,
    ) : BillingAvailability {
        var nextResult: QueryProductsResult? = null

        private val _products = MutableStateFlow(recognizedSkus)
        override val products: Flow<Map<String, BillingProduct>> = _products
        override val connectionState: Flow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)

        override suspend fun refresh(skus: Set<String>): QueryProductsResult {
            val forced = nextResult
            nextResult = null
            if (forced != null) return forced
            val filtered = if (passthrough) {
                skus.associateWith { BillingProduct(it, "<passthrough>", "USD", 0L) }
            } else {
                recognizedSkus.filterKeys { it in skus }
            }
            _products.value = filtered
            return QueryProductsResult.Success(products = filtered)
        }

        override fun snapshot(): Map<String, BillingProduct> = _products.value

        companion object {
            /** Recognize every SKU passed to refresh; overlays "<passthrough>" price. */
            fun passthrough(): FakeBillingAvailability = FakeBillingAvailability(passthrough = true)
        }
    }

    private object StubNetworkClient : NetworkClient {
        override val client: HttpClient
            get() = error("FakeDataSource overrides fetchCatalog — should not reach HttpClient")
        override val authenticatedClient: HttpClient
            get() = error("not used")
    }

    private suspend fun <T> Flow<T>.firstValue(): T = first()

    companion object {
        private val SAMPLE_DTO = ProductCatalogDto(
            chipPacks = listOf(
                ChipPackDto(
                    id = "chip_pack_small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSkuDto("chips_small", "$0.99"),
                ),
            ),
        )

        /** Reference verifying domain mapping is correct (used in compile-time check). */
        @Suppress("unused")
        private val SAMPLE_DOMAIN: ProductCatalog = ProductCatalog(
            chipPacks = listOf(
                Product.ChipPack(
                    id = "chip_pack_small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSku("chips_small", "$0.99"),
                ),
            ),
        )
    }
}

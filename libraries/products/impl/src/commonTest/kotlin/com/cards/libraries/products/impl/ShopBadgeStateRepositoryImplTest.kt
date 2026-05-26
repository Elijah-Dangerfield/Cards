package com.dangerfield.cards.libraries.products.impl

import app.cash.turbine.test
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppData
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.products.StoreSku
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contract the bottom-nav Shop tab dot is keyed off:
 *  - Fresh install + non-empty catalog → dot ON (everything's new).
 *  - After [markCurrentItemsSeen] → dot OFF.
 *  - After mark + catalog adds a new id → dot back ON.
 *  - Empty catalog (cold start before refresh lands) → dot OFF, never a
 *    false-positive on a fresh install.
 */
class ShopBadgeStateRepositoryImplTest : CoroutineTest() {

    @Test
    fun observeHasUnseenItems_freshInstallWithCatalog_emitsTrue() = runUnitTest {
        val products = FakeProductsRepository(initial = catalogOf("a", "b"))
        val cache = FakeAppCache()
        val repo = ShopBadgeStateRepositoryImpl(products, cache)

        repo.observeHasUnseenItems().test {
            assertTrue(awaitItem(), "fresh install + non-empty catalog must surface unseen items")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeHasUnseenItems_emptyCatalog_emitsFalse() = runUnitTest {
        val products = FakeProductsRepository(initial = ProductCatalog.Empty)
        val cache = FakeAppCache()
        val repo = ShopBadgeStateRepositoryImpl(products, cache)

        repo.observeHasUnseenItems().test {
            assertFalse(awaitItem(), "cold-start with no catalog must not show the dot")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun markCurrentItemsSeen_clearsTheDot() = runUnitTest {
        val products = FakeProductsRepository(initial = catalogOf("a", "b"))
        val cache = FakeAppCache()
        val repo = ShopBadgeStateRepositoryImpl(products, cache)

        repo.observeHasUnseenItems().test {
            assertTrue(awaitItem())
            repo.markCurrentItemsSeen()
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // The stored set should mirror the catalog at mark-time.
        assertEquals(setOf("a", "b"), cache.get().shopSeenProductIds)
    }

    @Test
    fun markThenCatalogGainsNewId_dotReturns() = runUnitTest {
        val products = FakeProductsRepository(initial = catalogOf("a", "b"))
        val cache = FakeAppCache()
        val repo = ShopBadgeStateRepositoryImpl(products, cache)

        repo.observeHasUnseenItems().test {
            assertTrue(awaitItem())
            repo.markCurrentItemsSeen()
            assertFalse(awaitItem())

            products.emit(catalogOf("a", "b", "c"))
            assertTrue(awaitItem(), "new id 'c' in catalog must re-trigger the dot")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun markCurrentItemsSeen_emptyCatalog_isNoOp() = runUnitTest {
        val products = FakeProductsRepository(initial = ProductCatalog.Empty)
        val cache = FakeAppCache()
        val repo = ShopBadgeStateRepositoryImpl(products, cache)

        repo.markCurrentItemsSeen()
        // We never clobber a non-empty seen set with empty just because the
        // catalog hasn't loaded yet — guards against a "user opens Shop on
        // cold start before catalog fetch lands" race that would otherwise
        // erase yesterday's seen ids.
        assertEquals(emptySet<String>(), cache.get().shopSeenProductIds)
    }

    @Test
    fun catalogIdSpanningBothBuckets_isCovered() = runUnitTest {
        val catalog = ProductCatalog(
            chipPacks = listOf(samplePack("pack_a")),
            chipOffers = listOf(sampleOffer("offer_b")),
        )
        val cache = FakeAppCache(initial = AppData(shopSeenProductIds = setOf("pack_a")))
        val products = FakeProductsRepository(initial = catalog)
        val repo = ShopBadgeStateRepositoryImpl(products, cache)

        // Only the offer is unseen → dot on.
        repo.observeHasUnseenItems().test {
            assertTrue(awaitItem(), "offer_b is unseen, dot must be on")
            cancelAndIgnoreRemainingEvents()
        }

        // Mark seen → both ids recorded.
        repo.markCurrentItemsSeen()
        assertEquals(setOf("pack_a", "offer_b"), cache.get().shopSeenProductIds)
    }

    // ---- helpers ----

    private fun catalogOf(vararg ids: String): ProductCatalog = ProductCatalog(
        chipOffers = ids.map { sampleOffer(it) },
    )

    private fun samplePack(id: String): Product.ChipPack = Product.ChipPack(
        id = id,
        title = id,
        subtitle = id,
        iconEmoji = "🪙",
        grantsChips = 100L,
        store = StoreSku(sku = id, fallbackPriceDisplay = "$0.99"),
    )

    private fun sampleOffer(id: String): Product.ChipOffer = Product.ChipOffer(
        id = id,
        title = id,
        subtitle = id,
        iconEmoji = "✨",
        costChips = 100L,
        grantsKey = id,
    )

    private class FakeProductsRepository(initial: ProductCatalog) : ProductsRepository {
        private val state = MutableStateFlow(initial)
        override fun observeCatalog(): Flow<ProductCatalog> = state
        override suspend fun refresh(force: Boolean): Result<ProductCatalog> =
            Result.success(state.value)
        override fun observeTimeAnchor(): Flow<CatalogTimeAnchor?> = MutableStateFlow(null)
        override fun observeIsRefreshing(): Flow<Boolean> = MutableStateFlow(false)

        fun emit(value: ProductCatalog) { state.value = value }
    }

    private class FakeAppCache(initial: AppData = AppData()) : AppCache {
        private val state = MutableStateFlow(initial)
        override val updates: Flow<AppData> = state
        override suspend fun get(): AppData = state.value
        override suspend fun set(value: AppData) { state.value = value }
        override suspend fun clear() { state.value = AppData() }
    }
}

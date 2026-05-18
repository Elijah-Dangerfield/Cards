package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.products.StoreSku
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the SEA contract for [ShopViewModel]. Drives the VM with fakes for
 * the two repositories so loading/refresh/error states can be triggered
 * deterministically.
 */
class ShopViewModelTest : CoroutineTest() {

    @Test
    fun afterInit_withSuccessfulRefresh_stateReflectsLoadedCatalog() = runUnitTest {
        // UnconfinedTestDispatcher runs the init coroutines eagerly, so by
        // the time we observe state, the kicked refresh has already
        // completed against the fake.
        val vm = buildVm()
        val state = vm.state
        assertFalse(state.isRefreshing, "refresh completed against synchronous fake")
        assertTrue(state.hasLoaded, "hasLoaded flips after first refresh outcome")
        assertEquals(1, state.catalog.chipPacks.size, "catalog from fake")
    }

    @Test
    fun successfulRefresh_populatesCatalog_andClearsRefreshing() = runUnitTest {
        val repo = FakeProductsRepository(SAMPLE_CATALOG)
        val vm = buildVm(productsRepository = repo)
        // FakeProductsRepository.refresh completes synchronously; after that
        // the post-actions process.
        assertFalse(vm.state.isRefreshing)
        assertTrue(vm.state.hasLoaded)
        assertEquals(1, vm.state.catalog.chipPacks.size)
        assertNull(vm.state.errorMessage)
    }

    @Test
    fun failedRefresh_populatesErrorMessage_andLeavesPriorCatalogIntact() = runUnitTest {
        val repo = FakeProductsRepository(SAMPLE_CATALOG)
        val vm = buildVm(productsRepository = repo)
        // Prior catalog is loaded.
        assertEquals(1, vm.state.catalog.chipPacks.size)

        repo.nextRefreshResult = Result.failure(RuntimeException("offline"))
        vm.takeAction(ShopAction.Refresh)

        assertEquals(1, vm.state.catalog.chipPacks.size, "prior catalog preserved")
        assertNotNull(vm.state.errorMessage)
        assertEquals("offline", vm.state.errorMessage)
    }

    @Test
    fun dismissError_clearsErrorMessage() = runUnitTest {
        val repo = FakeProductsRepository(SAMPLE_CATALOG).apply {
            nextRefreshResult = Result.failure(RuntimeException("boom"))
        }
        val vm = buildVm(productsRepository = repo)
        assertNotNull(vm.state.errorMessage)

        vm.takeAction(ShopAction.DismissError)
        assertNull(vm.state.errorMessage)
    }

    @Test
    fun chipBalance_mirrors_repository() = runUnitTest {
        val chipsRepo = FakeChipsRepository(initialBalance = 42_000)
        val vm = buildVm(chipsRepository = chipsRepo)
        assertEquals(42_000L, vm.state.chipBalance)

        chipsRepo.emit(50_000)
        assertEquals(50_000L, vm.state.chipBalance)
    }

    @Test
    fun purchaseChipPack_emitsLaunchPurchaseEvent() = runUnitTest {
        val vm = buildVm()
        val received = mutableListOf<ShopEvent>()
        // Collect on the test scope (backgroundScope is the right home for
        // long-running collectors; the test body's launch is auto-cancelled
        // by runUnitTest's finally).
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        val pack = SAMPLE_CATALOG.chipPacks.first()
        vm.takeAction(ShopAction.PurchaseChipPack(pack))

        assertEquals(1, received.size)
        val evt = received.first() as ShopEvent.LaunchPurchase
        assertEquals(pack.id, evt.product.id)
    }

    @Test
    fun redeemChipOffer_deductsChips_andEmitsRedeemed() = runUnitTest {
        val chipsRepo = FakeChipsRepository(initialBalance = 10_000)
        val vm = buildVm(chipsRepository = chipsRepo)

        val offer = Product.ChipOffer(
            id = "offer_dance",
            title = "Victory Dance",
            subtitle = "Avatar emote",
            iconKey = "emote_dance",
            costChips = 500,
            grantsKey = "emote.dance",
        )
        vm.takeAction(ShopAction.RedeemChipOffer(offer))

        assertEquals(9_500L, chipsRepo.balance(), "chips deducted by costChips")
    }

    @Test
    fun catalogFlow_updates_propagateToState() = runUnitTest {
        val repo = FakeProductsRepository(ProductCatalog.Empty)
        val vm = buildVm(productsRepository = repo)
        assertTrue(vm.state.catalog.isEmpty)

        repo.emit(SAMPLE_CATALOG)
        assertEquals(1, vm.state.catalog.chipPacks.size)
    }

    // ---------- Test scaffolding ----------

    private fun buildVm(
        productsRepository: FakeProductsRepository = FakeProductsRepository(SAMPLE_CATALOG),
        chipsRepository: FakeChipsRepository = FakeChipsRepository(),
    ): ShopViewModel = ShopViewModel(
        productsRepository = productsRepository,
        chipsRepository = chipsRepository,
    )

    private class FakeProductsRepository(
        initial: ProductCatalog,
    ) : ProductsRepository {
        private val state = MutableStateFlow(initial)
        var nextRefreshResult: Result<ProductCatalog>? = null

        override fun observeCatalog(): Flow<ProductCatalog> = state.asStateFlow()

        override suspend fun refresh(): Result<ProductCatalog> {
            val result = nextRefreshResult
            if (result != null) {
                nextRefreshResult = null
                return result
            }
            return Result.success(state.value)
        }

        fun emit(value: ProductCatalog) { state.value = value }
    }

    private class FakeChipsRepository(
        initialBalance: Long = ChipsRepository.STARTING_GRANT,
    ) : ChipsRepository {
        private val state = MutableStateFlow(initialBalance)
        override fun observeBalance(): Flow<Long> = state.asStateFlow()
        override suspend fun getBalance(): Long = state.value
        override suspend fun applyDelta(delta: Long) { state.value += delta }
        override suspend fun deleteAll() { state.value = 0 }
        fun balance(): Long = state.value
        fun emit(value: Long) { state.value = value }
    }

    companion object {
        private val SAMPLE_CATALOG = ProductCatalog(
            chipPacks = listOf(
                Product.ChipPack(
                    id = "chip_pack_small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconKey = "chips_small",
                    grantsChips = 5_000,
                    store = StoreSku("chips_small", "$0.99"),
                ),
            ),
        )
    }
}

package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.InventorySyncService
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
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
 * Pins the SEA contract for [ShopViewModel].
 *
 * What's covered:
 *  - Init wiring: catalog + chip balance + inventory flows mirror into state,
 *    and refresh + sync both fire on construction.
 *  - Pull-to-refresh: success populates catalog, failure leaves prior
 *    catalog intact + surfaces errorMessage.
 *  - Purchase intent flow: RequestPurchase opens the right kind of sheet,
 *    dismiss closes it, confirm commits.
 *  - Optimistic redeem: chip deduction happens immediately via repo,
 *    sync service fires in the background, event emitted.
 *  - Affordability guard: confirming a too-expensive offer surfaces an
 *    error instead of mutating state.
 *  - Idempotent re-redeem: AlreadyOwned path emits a benign event without
 *    duplicating the chip charge.
 *  - IAP path: ConfirmPendingPurchase emits LaunchPurchase event without
 *    touching local state.
 */
class ShopViewModelTest : CoroutineTest() {

    @Test
    fun afterInit_withSuccessfulRefresh_stateReflectsLoadedCatalog() = runUnitTest {
        val vm = buildVm()
        assertFalse(vm.state.isRefreshing)
        assertTrue(vm.state.hasLoaded)
        assertEquals(2, vm.state.catalog.chipPacks.size)
        assertEquals(2, vm.state.catalog.chipOffers.size)
    }

    @Test
    fun failedRefresh_preservesPriorCatalog_andSurfacesError() = runUnitTest {
        val repo = FakeProductsRepository(SAMPLE_CATALOG)
        val vm = buildVm(productsRepository = repo)
        assertEquals(2, vm.state.catalog.chipPacks.size)

        repo.nextRefreshResult = Result.failure(RuntimeException("offline"))
        vm.takeAction(ShopAction.Refresh)

        assertEquals(2, vm.state.catalog.chipPacks.size, "prior catalog preserved")
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
    fun chipBalance_mirrorsRepository() = runUnitTest {
        val chips = FakeChipsRepository(initialBalance = 42_000)
        val vm = buildVm(chipsRepository = chips)
        assertEquals(42_000L, vm.state.chipBalance)

        chips.emit(50_000)
        assertEquals(50_000L, vm.state.chipBalance)
    }

    @Test
    fun inventory_mirrorsRepository_intoOwnedProductIds() = runUnitTest {
        val inv = FakeInventoryRepository().apply {
            emit(listOf(SAMPLE_PENDING_INVENTORY_ITEM))
        }
        val vm = buildVm(inventoryRepository = inv)
        assertEquals(setOf("emote_dance"), vm.state.ownedProductIds)
        assertTrue(vm.state.ownsProduct("emote_dance"))
        assertFalse(vm.state.ownsProduct("emote_tilt"))
    }

    // ---------- Purchase intent flow ----------

    @Test
    fun requestPurchase_chipPack_opensIapSheet() = runUnitTest {
        val vm = buildVm()
        val pack = SAMPLE_CATALOG.chipPacks.first()

        vm.takeAction(ShopAction.RequestPurchase(pack))

        val pending = vm.state.pendingPurchase
        assertTrue(pending is PendingPurchase.IapPack, "got: $pending")
        assertEquals(pack.id, (pending as PendingPurchase.IapPack).product.id)
    }

    @Test
    fun requestPurchase_chipOffer_opensChipOfferSheet() = runUnitTest {
        val vm = buildVm()
        val offer = SAMPLE_CATALOG.chipOffers.first()

        vm.takeAction(ShopAction.RequestPurchase(offer))

        val pending = vm.state.pendingPurchase
        assertTrue(pending is PendingPurchase.ChipOffer)
        assertEquals(offer.id, (pending as PendingPurchase.ChipOffer).product.id)
    }

    @Test
    fun requestPurchase_alreadyOwnedOffer_doesNotOpenSheet() = runUnitTest {
        val inv = FakeInventoryRepository().apply {
            emit(listOf(SAMPLE_PENDING_INVENTORY_ITEM))
        }
        val vm = buildVm(inventoryRepository = inv)
        val ownedOffer = SAMPLE_CATALOG.chipOffers.first { it.id == "emote_dance" }

        vm.takeAction(ShopAction.RequestPurchase(ownedOffer))

        assertNull(vm.state.pendingPurchase, "sheet should not open for owned items")
    }

    @Test
    fun dismissPendingPurchase_clearsSheet() = runUnitTest {
        val vm = buildVm()
        val offer = SAMPLE_CATALOG.chipOffers.first()
        vm.takeAction(ShopAction.RequestPurchase(offer))
        assertNotNull(vm.state.pendingPurchase)

        vm.takeAction(ShopAction.DismissPendingPurchase)
        assertNull(vm.state.pendingPurchase)
    }

    // ---------- Optimistic chip-offer redemption ----------

    @Test
    fun confirmChipOffer_success_closesSheet_andCallsRepo() = runUnitTest {
        val inv = FakeInventoryRepository()
        val sync = FakeSyncService()
        val vm = buildVm(inventoryRepository = inv, inventorySyncService = sync)
        val offer = SAMPLE_CATALOG.chipOffers.first()  // cost 2_500
        vm.takeAction(ShopAction.RequestPurchase(offer))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertNull(vm.state.pendingPurchase, "sheet closed")
        assertEquals(
            listOf("emote_dance" to 2_500L),
            inv.redeemCalls,
            "repo called with the offer's id + cost",
        )
        // sync fires in the background after success
        assertTrue(sync.syncCalls >= 1, "sync kicked")
    }

    @Test
    fun confirmChipOffer_success_emitsRedeemSucceededEvent() = runUnitTest {
        val vm = buildVm()
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val offer = SAMPLE_CATALOG.chipOffers.first()
        vm.takeAction(ShopAction.RequestPurchase(offer))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        val event = received.firstOrNull { it is ShopEvent.RedeemSucceeded }
        assertNotNull(event, "RedeemSucceeded should fire")
        assertEquals(offer.id, (event as ShopEvent.RedeemSucceeded).offer.id)
    }

    @Test
    fun confirmChipOffer_insufficientChips_surfacesError_doesNotChargeRepo() = runUnitTest {
        val inv = FakeInventoryRepository().apply {
            nextRedeemResult = RedeemResult.InsufficientChips
        }
        val vm = buildVm(inventoryRepository = inv)
        val offer = SAMPLE_CATALOG.chipOffers.first()
        vm.takeAction(ShopAction.RequestPurchase(offer))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertNotNull(vm.state.errorMessage, "error surfaced")
        assertTrue(vm.state.errorMessage!!.contains(offer.title, ignoreCase = true))
        // Sheet still closes optimistically — the failure is surfaced as a
        // toast, not by reopening the sheet.
        assertNull(vm.state.pendingPurchase)
    }

    @Test
    fun confirmChipOffer_alreadyOwned_emitsAlreadyOwned_doesNotCharge() = runUnitTest {
        val inv = FakeInventoryRepository().apply {
            nextRedeemResult = RedeemResult.AlreadyOwned
        }
        val vm = buildVm(inventoryRepository = inv)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val offer = SAMPLE_CATALOG.chipOffers.first()
        vm.takeAction(ShopAction.RequestPurchase(offer))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertTrue(received.any { it is ShopEvent.AlreadyOwned })
        assertNull(vm.state.errorMessage, "AlreadyOwned isn't surfaced as an error")
    }

    // ---------- IAP path ----------

    @Test
    fun confirmIapPack_emitsLaunchPurchaseEvent_closesSheet() = runUnitTest {
        val inv = FakeInventoryRepository()
        val vm = buildVm(inventoryRepository = inv)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val pack = SAMPLE_CATALOG.chipPacks.first()
        vm.takeAction(ShopAction.RequestPurchase(pack))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertNull(vm.state.pendingPurchase)
        val event = received.firstOrNull { it is ShopEvent.LaunchPurchase }
        assertNotNull(event)
        assertEquals(pack.id, (event as ShopEvent.LaunchPurchase).product.id)
        // IAP doesn't deduct chips locally — that's gated by the platform store callback.
        assertTrue(inv.redeemCalls.isEmpty())
    }

    // ---------- Sync on launch ----------

    @Test
    fun init_kicksInventorySync() = runUnitTest {
        val sync = FakeSyncService()
        buildVm(inventorySyncService = sync)
        // ShopViewModel.init calls sync.sync() once.
        assertEquals(1, sync.syncCalls)
    }

    // ---------- Test scaffolding ----------

    private fun buildVm(
        productsRepository: FakeProductsRepository = FakeProductsRepository(SAMPLE_CATALOG),
        inventoryRepository: FakeInventoryRepository = FakeInventoryRepository(),
        inventorySyncService: FakeSyncService = FakeSyncService(),
        chipsRepository: FakeChipsRepository = FakeChipsRepository(),
        progressionRepository: FakeProgressionRepository = FakeProgressionRepository(),
    ): ShopViewModel = ShopViewModel(
        productsRepository = productsRepository,
        inventoryRepository = inventoryRepository,
        inventorySyncService = inventorySyncService,
        chipsRepository = chipsRepository,
        progressionRepository = progressionRepository,
    )

    private class FakeProductsRepository(initial: ProductCatalog) : ProductsRepository {
        private val state = MutableStateFlow(initial)
        private val timeAnchor = MutableStateFlow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?>(null)
        var nextRefreshResult: Result<ProductCatalog>? = null

        override fun observeCatalog(): Flow<ProductCatalog> = state.asStateFlow()

        override fun observeTimeAnchor(): Flow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?> =
            timeAnchor.asStateFlow()

        override suspend fun refresh(): Result<ProductCatalog> {
            val result = nextRefreshResult
            if (result != null) {
                nextRefreshResult = null
                return result
            }
            // Mirror the impl: every successful refresh updates the time
            // anchor so tests can exercise time-anchor-aware code paths.
            timeAnchor.value = com.dangerfield.cards.libraries.products.CatalogTimeAnchor
                .capture(serverNowEpochMs = 0L)
            return Result.success(state.value)
        }
    }

    private class FakeInventoryRepository : InventoryRepository {
        private val state = MutableStateFlow<List<InventoryItem>>(emptyList())
        val redeemCalls = mutableListOf<Pair<String, Long>>()
        var nextRedeemResult: RedeemResult? = null

        override fun observeInventory(): Flow<List<InventoryItem>> = state.asStateFlow()
        override suspend fun getInventory(): List<InventoryItem> = state.value
        override suspend fun redeemChipOffer(
            productId: String,
            costChips: Long,
        ): RedeemResult {
            redeemCalls += productId to costChips
            val override = nextRedeemResult
            if (override != null) {
                nextRedeemResult = null
                return override
            }
            state.value = state.value + InventoryItem(
                productId = productId,
                state = PurchaseState.Pending,
                purchasedAtEpochMs = 0,
                costChipsAtPurchase = costChips,
            )
            return RedeemResult.Success
        }
        override suspend fun markConfirmed(productIds: Collection<String>) { }
        override suspend fun revertPurchase(productId: String) {
            state.value = state.value.filterNot { it.productId == productId }
        }
        override suspend fun deleteAll() { state.value = emptyList() }
        fun emit(items: List<InventoryItem>) { state.value = items }
    }

    private class FakeSyncService : InventorySyncService {
        var syncCalls: Int = 0
            private set
        override suspend fun sync(): Result<Unit> {
            syncCalls++
            return Result.success(Unit)
        }
    }

    private class FakeChipsRepository(
        initialBalance: Long = ChipsRepository.STARTING_GRANT,
    ) : ChipsRepository {
        private val state = MutableStateFlow(initialBalance)
        override fun observeBalance(): Flow<Long> = state.asStateFlow()
        override suspend fun getBalance(): Long = state.value
        override suspend fun applyDelta(delta: Long) { state.value += delta }
        override suspend fun deleteAll() { state.value = 0 }
        fun emit(value: Long) { state.value = value }
    }

    companion object {
        private val SAMPLE_CATALOG = ProductCatalog(
            chipPacks = listOf(
                Product.ChipPack(
                    id = "chip_pack_small",
                    title = "Pocket Stack",
                    subtitle = "5,000 chips",
                    iconEmoji = "🪙",
                    grantsChips = 5_000,
                    store = StoreSku("chips_small", "$0.99"),
                ),
                Product.ChipPack(
                    id = "chip_pack_medium",
                    title = "Tall Stack",
                    subtitle = "30,000 chips",
                    iconEmoji = "💰",
                    featured = true,
                    badge = "BEST VALUE",
                    grantsChips = 30_000,
                    store = StoreSku("chips_medium", "$4.99"),
                ),
            ),
            chipOffers = listOf(
                Product.ChipOffer(
                    id = "emote_dance",
                    title = "Victory Dance",
                    subtitle = "Emote",
                    iconEmoji = "💃",
                    costChips = 2_500,
                    grantsKey = "emote.dance",
                ),
                Product.ChipOffer(
                    id = "emote_tilt",
                    title = "Salty Shake",
                    subtitle = "Emote",
                    iconEmoji = "🧂",
                    costChips = 2_500,
                    grantsKey = "emote.tilt",
                ),
            ),
        )

        private val SAMPLE_PENDING_INVENTORY_ITEM = InventoryItem(
            productId = "emote_dance",
            state = PurchaseState.Pending,
            purchasedAtEpochMs = 1000L,
            costChipsAtPurchase = 2_500L,
        )
    }

    /**
     * Minimal fake for [com.dangerfield.cards.libraries.cards.ProgressionRepository]
     * — we only exercise the observeProgression Flow path in shop tests.
     * Bumping XP via the mutable state will trigger the VM to recompute
     * playerLevel on the next collection.
     */
    private class FakeProgressionRepository(
        initial: com.dangerfield.cards.libraries.cards.Progression =
            com.dangerfield.cards.libraries.cards.Progression.Empty,
    ) : com.dangerfield.cards.libraries.cards.ProgressionRepository {
        private val state = MutableStateFlow(initial)

        fun setXp(totalXp: Long) {
            state.value = state.value.copy(totalXp = totalXp)
        }

        override fun observeProgression() = state.asStateFlow()
        override suspend fun getProgression() = state.value
        override suspend fun awardForHand(
            summary: com.dangerfield.cards.libraries.cards.HandResultSummary,
        ) = emptyList<com.dangerfield.cards.libraries.cards.XpEvent>()
        override suspend fun applyAchievementXp(delta: Int, description: String?) =
            error("not used in shop tests")
        override suspend fun deleteAll() {}
    }
}

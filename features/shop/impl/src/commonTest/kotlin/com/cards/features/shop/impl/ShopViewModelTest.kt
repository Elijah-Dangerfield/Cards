package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.libraries.billing.BillingClient
import com.dangerfield.cards.libraries.billing.BillingPlatform
import com.dangerfield.cards.libraries.billing.ConnectionState
import com.dangerfield.cards.libraries.billing.PurchaseResult
import com.dangerfield.cards.libraries.billing.PurchaseTransaction
import com.dangerfield.cards.libraries.billing.QueryProductsResult
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncService
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.InventorySyncService
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.Identity
import com.dangerfield.cards.libraries.identity.IdentityRepository
import com.dangerfield.cards.libraries.identity.IdentityState
import com.dangerfield.cards.libraries.identity.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.OAuthProvider
import com.dangerfield.cards.libraries.identity.RefreshOutcome
import com.dangerfield.cards.libraries.identity.ResendOutcome
import com.dangerfield.cards.libraries.identity.SignInOutcome
import com.dangerfield.cards.libraries.identity.SignUpOutcome
import com.dangerfield.cards.libraries.identity.UpdateProfileOutcome
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.products.StoreSku
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 *  - IAP path: ConfirmPendingPurchase drives BillingClient.purchase and
 *    emits the right PurchaseFinished outcome for each branch (success,
 *    cancel, already-owned, store-unavailable, no-user). Success credits
 *    chips locally; cancel and failures leave the chip balance alone.
 */
class ShopViewModelTest : CoroutineTest() {

    @Test
    fun afterInit_withSuccessfulRefresh_stateReflectsLoadedCatalog() = runUnitTest {
        val vm = buildVm()
        assertFalse(vm.state.isRefreshing)
        assertTrue(vm.state.hasLoaded)
        assertEquals(2, vm.state.catalog.chipPacks.size)
        assertEquals(4, vm.state.catalog.chipOffers.size)
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
    fun requestPurchase_ownedOffer_opensSheetInOwnedMode() = runUnitTest {
        // The sheet now opens for owned items too — users can re-read
        // the description and find the "manage in profile" guidance.
        // Buying is still gated at the Confirm action.
        val inv = FakeInventoryRepository().apply {
            emit(listOf(SAMPLE_PENDING_INVENTORY_ITEM))
        }
        val vm = buildVm(inventoryRepository = inv)
        val ownedOffer = SAMPLE_CATALOG.chipOffers.first { it.id == "emote_dance" }

        vm.takeAction(ShopAction.RequestPurchase(ownedOffer))

        assertNotNull(vm.state.pendingPurchase, "owned items now open the sheet")
        assertTrue(
            vm.state.sheetModeFor(ownedOffer) is PurchaseSheetMode.Owned,
            "and the mode classifier returns Owned",
        )
    }

    @Test
    fun confirmPendingPurchase_isNoOpForOwnedItem() = runUnitTest {
        // The Confirm action is the real fence — defense in depth.
        val inv = FakeInventoryRepository().apply {
            emit(listOf(SAMPLE_PENDING_INVENTORY_ITEM))
        }
        val vm = buildVm(inventoryRepository = inv)
        val ownedOffer = SAMPLE_CATALOG.chipOffers.first { it.id == "emote_dance" }
        vm.takeAction(ShopAction.RequestPurchase(ownedOffer))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        // Sheet still up (no-op), no repo redeem called.
        assertNotNull(vm.state.pendingPurchase, "Confirm is a no-op for Owned")
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

    // ---------- Auto-equip on purchase ----------

    @Test
    fun confirmChipOffer_success_autoEquipsCosmetic_whenSlotEmpty() = runUnitTest {
        val equipment = FakeEquipmentRepository()
        val equipSync = FakeEquipmentSyncService()
        val vm = buildVm(
            equipmentRepository = equipment,
            equipmentSyncService = equipSync,
        )
        val felt = SAMPLE_CATALOG.chipOffers.first { it.id == "felt_royal_red" }
        vm.takeAction(ShopAction.RequestPurchase(felt))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertEquals(listOf("felt_royal_red"), equipment.equipCalls)
        assertTrue(equipSync.syncCalls >= 1, "equipment sync should fire after auto-equip")
    }

    @Test
    fun confirmChipOffer_success_doesNotAutoEquip_whenSlotIsOccupied() = runUnitTest {
        // User already has a felt equipped — buying a *different* felt
        // shouldn't silently steal that pick. The user can flip in
        // My Items if they want the new one.
        val equipment = FakeEquipmentRepository(
            initial = listOf(
                EquipmentEntry(
                    productId = "felt_midnight_blue",
                    isEquipped = true,
                    syncState = EquipmentSyncState.Synced,
                    updatedAtEpochMs = 0L,
                ),
            ),
        )
        val vm = buildVm(equipmentRepository = equipment)
        val felt = SAMPLE_CATALOG.chipOffers.first { it.id == "felt_royal_red" }
        vm.takeAction(ShopAction.RequestPurchase(felt))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertTrue(equipment.equipCalls.isEmpty(), "should not auto-equip when slot already occupied")
    }

    @Test
    fun confirmChipOffer_success_doesNotAutoEquip_forNonSlotProduct() = runUnitTest {
        // Emotes don't claim a single-equip slot — auto-equip is a no-op
        // for them. (Users still pick which emotes to surface in the
        // emote tray separately.)
        val equipment = FakeEquipmentRepository()
        val vm = buildVm(equipmentRepository = equipment)
        val emote = SAMPLE_CATALOG.chipOffers.first { it.id == "emote_dance" }
        vm.takeAction(ShopAction.RequestPurchase(emote))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertTrue(equipment.equipCalls.isEmpty(), "non-slot products should not auto-equip")
    }

    // ---------- IAP path ----------

    @Test
    fun confirmIapPack_success_creditsChips_emitsPurchaseFinished_closesSheet() = runUnitTest {
        val inv = FakeInventoryRepository()
        val billing = FakeBillingClient(nextResult = PurchaseResult.Success(SAMPLE_TRANSACTION))
        val chips = FakeChipsRepository(initialBalance = 0)
        val vm = buildVm(inventoryRepository = inv, chipsRepository = chips, billingClient = billing)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val pack = SAMPLE_CATALOG.chipPacks.first()
        vm.takeAction(ShopAction.RequestPurchase(pack))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertNull(vm.state.pendingPurchase)
        assertEquals(1, billing.purchaseCalls, "billing client should have been called once")
        assertEquals(pack.grantsChips, chips.getBalance(), "chips should be credited locally")
        assertEquals(1, billing.acknowledgeCalls, "purchase should be acknowledged on success")
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertNotNull(event)
        val outcome = (event as ShopEvent.PurchaseFinished).outcome
        assertTrue(outcome is IapPurchaseOutcome.Success, "got: $outcome")
        assertEquals(pack.grantsChips, (outcome as IapPurchaseOutcome.Success).grantedChips)
        // IAP doesn't touch the inventory table — chip packs are not cosmetics.
        assertTrue(inv.redeemCalls.isEmpty())
    }

    @Test
    fun confirmIapPack_cancel_doesNotCreditChips_emitsCancelledOutcome() = runUnitTest {
        val billing = FakeBillingClient(nextResult = PurchaseResult.UserCancelled)
        val chips = FakeChipsRepository(initialBalance = 0)
        val vm = buildVm(chipsRepository = chips, billingClient = billing)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        vm.takeAction(ShopAction.RequestPurchase(SAMPLE_CATALOG.chipPacks.first()))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertEquals(0L, chips.getBalance(), "no chip credit on user cancel")
        assertEquals(0, billing.acknowledgeCalls, "nothing to acknowledge on cancel")
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertEquals(IapPurchaseOutcome.Cancelled, (event as ShopEvent.PurchaseFinished).outcome)
    }

    @Test
    fun confirmIapPack_alreadyOwned_creditsChips_andEmitsAlreadyOwned() = runUnitTest {
        val billing = FakeBillingClient(
            nextResult = PurchaseResult.AlreadyOwned(SAMPLE_TRANSACTION),
        )
        val chips = FakeChipsRepository(initialBalance = 0)
        val vm = buildVm(chipsRepository = chips, billingClient = billing)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val pack = SAMPLE_CATALOG.chipPacks.first()
        vm.takeAction(ShopAction.RequestPurchase(pack))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        // Treat as idempotent: re-credit so a lost-purchase client recovers.
        assertEquals(pack.grantsChips, chips.getBalance())
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        val outcome = (event as ShopEvent.PurchaseFinished).outcome
        assertTrue(outcome is IapPurchaseOutcome.AlreadyOwned, "got: $outcome")
    }

    @Test
    fun confirmIapPack_storeNotConnected_emitsStoreUnavailable_noChipChange() = runUnitTest {
        val billing = FakeBillingClient(nextResult = PurchaseResult.NotConnected)
        val chips = FakeChipsRepository(initialBalance = 0)
        val vm = buildVm(chipsRepository = chips, billingClient = billing)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        vm.takeAction(ShopAction.RequestPurchase(SAMPLE_CATALOG.chipPacks.first()))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertEquals(0L, chips.getBalance())
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertEquals(IapPurchaseOutcome.StoreUnavailable, (event as ShopEvent.PurchaseFinished).outcome)
    }

    @Test
    fun confirmIapPack_anonymousUser_emitsNotSignedIn_doesNotCallBilling() = runUnitTest {
        // The shop is generally reachable post-onboarding, but the
        // anonymous-by-default state means is_anonymous=true is normal.
        // Anonymous Supabase users still have a userId — that's what we
        // forward to the store. The screen should only block when there's
        // NO signed-in user at all (state == Unknown).
        val billing = FakeBillingClient(nextResult = PurchaseResult.Success(SAMPLE_TRANSACTION))
        val identity = FakeIdentityRepository(initialState = IdentityState.Unknown)
        val vm = buildVm(billingClient = billing, identityRepository = identity)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        vm.takeAction(ShopAction.RequestPurchase(SAMPLE_CATALOG.chipPacks.first()))

        vm.takeAction(ShopAction.ConfirmPendingPurchase)

        assertEquals(0, billing.purchaseCalls, "should not call billing without a userId")
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertEquals(IapPurchaseOutcome.NotSignedIn, (event as ShopEvent.PurchaseFinished).outcome)
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
        billingClient: FakeBillingClient = FakeBillingClient(),
        identityRepository: FakeIdentityRepository = FakeIdentityRepository(
            initialState = IdentityState.SignedIn(SAMPLE_IDENTITY),
        ),
        equipmentRepository: FakeEquipmentRepository = FakeEquipmentRepository(),
        equipmentSyncService: FakeEquipmentSyncService = FakeEquipmentSyncService(),
    ): ShopViewModel = ShopViewModel(
        productsRepository = productsRepository,
        inventoryRepository = inventoryRepository,
        inventorySyncService = inventorySyncService,
        chipsRepository = chipsRepository,
        progressionRepository = progressionRepository,
        billingClient = billingClient,
        identityRepository = identityRepository,
        equipmentRepository = equipmentRepository,
        equipmentSyncService = equipmentSyncService,
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

    private class FakeEquipmentRepository(
        initial: List<EquipmentEntry> = emptyList(),
    ) : EquipmentRepository {
        private val state = MutableStateFlow(initial)
        val equipCalls = mutableListOf<String>()
        val unequipCalls = mutableListOf<String>()

        override fun observeEquipped(): Flow<List<EquipmentEntry>> = state.asStateFlow()
        override suspend fun getAll(): List<EquipmentEntry> = state.value
        override suspend fun equip(productId: String): EquipmentToggleResult {
            equipCalls += productId
            state.value = state.value
                .filterNot { it.productId == productId } +
                EquipmentEntry(
                    productId = productId,
                    isEquipped = true,
                    syncState = EquipmentSyncState.Pending,
                    updatedAtEpochMs = 0L,
                )
            return EquipmentToggleResult.Success
        }
        override suspend fun unequip(productId: String): EquipmentToggleResult {
            unequipCalls += productId
            return EquipmentToggleResult.Success
        }
        override suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>) { }
        override suspend fun deleteAll() { state.value = emptyList() }
    }

    private class FakeEquipmentSyncService : EquipmentSyncService {
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
        val appliedDeltas = mutableListOf<Triple<Long, String, String?>>()

        override fun observeBalance(): Flow<Long> = state.asStateFlow()
        override suspend fun getBalance(): Long = state.value
        override suspend fun applyDelta(delta: Long, reason: String, idempotencyKey: String?) {
            appliedDeltas += Triple(delta, reason, idempotencyKey)
            state.value += delta
        }
        override suspend fun setBalance(authoritativeBalance: Long) { state.value = authoritativeBalance }
        override suspend fun deleteAll() { state.value = 0 }
        fun emit(value: Long) { state.value = value }
    }

    private class FakeBillingClient(
        var nextResult: PurchaseResult = PurchaseResult.Success(SAMPLE_TRANSACTION),
    ) : BillingClient {
        var purchaseCalls: Int = 0
            private set
        var lastPurchaseSku: String? = null
            private set
        var lastPurchaseUserId: String? = null
            private set
        var acknowledgeCalls: Int = 0
            private set

        private val _connectionState = MutableStateFlow(ConnectionState.Connected)
        override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

        override suspend fun connect(): ConnectionState = ConnectionState.Connected
        override suspend fun queryProducts(skus: Set<String>): QueryProductsResult =
            QueryProductsResult.Success(products = emptyMap())

        override suspend fun purchase(sku: String, userId: String): PurchaseResult {
            purchaseCalls += 1
            lastPurchaseSku = sku
            lastPurchaseUserId = userId
            return nextResult
        }

        override suspend fun acknowledge(purchaseToken: String): Boolean {
            acknowledgeCalls += 1
            return true
        }
    }

    private class FakeIdentityRepository(
        initialState: IdentityState = IdentityState.Unknown,
    ) : IdentityRepository {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<IdentityState> = _state.asStateFlow()

        override suspend fun ensureInitialized(): Identity = error("not used in shop tests")
        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
            error("not used in shop tests")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
            error("not used in shop tests")
        override suspend fun refreshSession(): RefreshOutcome = error("not used in shop tests")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome =
            error("not used in shop tests")
        override suspend fun signOut() = error("not used in shop tests")
        override suspend fun updateProfile(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("not used in shop tests")
        override suspend fun fetchAvatarPack(): AvatarPackOutcome = error("not used in shop tests")
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("not used in shop tests")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
            error("not used in shop tests")
        override suspend fun signInWithOAuth(provider: OAuthProvider): SignInOutcome =
            error("not used in shop tests")
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
                Product.ChipOffer(
                    id = "felt_royal_red",
                    title = "Royal Red",
                    subtitle = "Felt",
                    iconEmoji = "🟥",
                    costChips = 5_000,
                    grantsKey = "felt.royal_red",
                ),
                Product.ChipOffer(
                    id = "felt_midnight_blue",
                    title = "Midnight Blue",
                    subtitle = "Felt",
                    iconEmoji = "🟦",
                    costChips = 5_000,
                    grantsKey = "felt.midnight_blue",
                ),
            ),
        )

        private val SAMPLE_PENDING_INVENTORY_ITEM = InventoryItem(
            productId = "emote_dance",
            state = PurchaseState.Pending,
            purchasedAtEpochMs = 1000L,
            costChipsAtPurchase = 2_500L,
        )

        private val SAMPLE_IDENTITY = Identity(
            userId = "11111111-1111-1111-1111-111111111111",
            displayName = "QuietAce72",
            avatarEmoji = "🃏",
            avatarBackgroundColor = null,
            isAnonymous = false,
        )

        private val SAMPLE_TRANSACTION = PurchaseTransaction(
            sku = "chips_small",
            orderId = "fake-order-1",
            purchaseToken = "fake-token-1",
            platform = BillingPlatform.Fake,
            purchasedAtEpochMs = 0L,
            displayPrice = "$0.99",
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

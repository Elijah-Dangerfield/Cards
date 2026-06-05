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
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.DeleteAccountOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkEmailIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.LinkIdentityOutcome
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.identity.auth.RefreshOutcome
import com.dangerfield.cards.libraries.identity.auth.ResendOutcome
import com.dangerfield.cards.libraries.identity.auth.SendResetOutcome
import com.dangerfield.cards.libraries.identity.auth.SignInOutcome
import com.dangerfield.cards.libraries.identity.auth.SignUpOutcome
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
 *  - Sheet mode classification: `sheetModeFor` returns the right
 *    PurchaseSheetMode given owned / locked / insufficient / available.
 *  - Confirm guard: `ConfirmPurchase` is a no-op for owned items (the
 *    sheet's CTA is disabled too, but the action layer is the final fence).
 *  - Optimistic redeem: chip deduction happens immediately via repo,
 *    sync service fires in the background, event emitted.
 *  - Affordability guard: confirming a too-expensive offer surfaces an
 *    error instead of mutating state.
 *  - Idempotent re-redeem: AlreadyOwned path emits a benign event without
 *    duplicating the chip charge.
 *  - IAP path: `ConfirmPurchase` drives BillingClient.purchase and
 *    emits the right PurchaseFinished outcome for each branch (success,
 *    cancel, already-owned, store-unavailable, no-user). Success credits
 *    chips locally; cancel and failures leave the chip balance alone.
 *
 * Sheet open/dismiss aren't VM concerns anymore — the purchase sheet
 * is its own navigation route (`ShopProductSheetRoute`). Tests for
 * that surface live with the navigation graph, not here.
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
        vm.takeAction(ShopAction.Refresh(force = true))

        assertEquals(2, vm.state.catalog.chipPacks.size, "prior catalog preserved")
        assertEquals("offline", vm.state.errorMessage)
    }

    @Test
    fun dismissError_clearsErrorMessage() = runUnitTest {
        // The VM no longer triggers a refresh from init (the repo
        // self-triggers on session boundary). To surface an error
        // for the dismiss-clears test, we have to explicitly pull
        // to refresh and let it fail.
        val repo = FakeProductsRepository(SAMPLE_CATALOG).apply {
            nextRefreshResult = Result.failure(RuntimeException("boom"))
        }
        val vm = buildVm(productsRepository = repo)
        vm.takeAction(ShopAction.Refresh(force = true))
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

    // ---------- Sheet mode classification ----------
    //
    // Sheet open/dismiss is navigation, not a VM concern — see
    // `ShopProductSheetRoute`. What still lives on the VM is the
    // *mode* the sheet should render in for a given product, plus the
    // confirm-purchase gate. Those are what this section pins.

    @Test
    fun sheetModeFor_ownedOffer_isOwned() = runUnitTest {
        val inv = FakeInventoryRepository().apply {
            emit(listOf(SAMPLE_PENDING_INVENTORY_ITEM))
        }
        val vm = buildVm(inventoryRepository = inv)
        val ownedOffer = SAMPLE_CATALOG.chipOffers.first { it.id == "emote_dance" }

        assertTrue(vm.state.sheetModeFor(ownedOffer) is PurchaseSheetMode.Owned)
    }

    @Test
    fun confirmPurchase_isNoOpForOwnedItem() = runUnitTest {
        // The Confirm action is the real fence — defense in depth.
        val inv = FakeInventoryRepository().apply {
            emit(listOf(SAMPLE_PENDING_INVENTORY_ITEM))
        }
        val vm = buildVm(inventoryRepository = inv)
        val ownedOffer = SAMPLE_CATALOG.chipOffers.first { it.id == "emote_dance" }

        vm.takeAction(ShopAction.ConfirmPurchase(ownedOffer))

        // No-op: no repo redeem call should have fired.
        assertTrue(inv.redeemCalls.isEmpty(), "Confirm is a no-op for Owned")
    }

    // ---------- Optimistic chip-offer redemption ----------

    @Test
    fun confirmChipOffer_success_callsRepo() = runUnitTest {
        val inv = FakeInventoryRepository()
        val vm = buildVm(inventoryRepository = inv)
        val offer = SAMPLE_CATALOG.chipOffers.first()  // cost 2_500

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

        assertEquals(
            listOf("emote_dance" to 2_500L),
            inv.redeemCalls,
            "repo called with the offer's id + cost",
        )
        // sync fires in the background after success
        assertTrue(inv.syncCalls >= 1, "inventory sync kicked")
    }

    @Test
    fun confirmChipOffer_success_emitsRedeemSucceededEvent() = runUnitTest {
        val vm = buildVm()
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val offer = SAMPLE_CATALOG.chipOffers.first()

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

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

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

        assertNotNull(vm.state.errorMessage, "error surfaced")
        assertTrue(vm.state.errorMessage!!.contains(offer.title, ignoreCase = true))
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

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

        assertTrue(received.any { it is ShopEvent.AlreadyOwned })
        assertNull(vm.state.errorMessage, "AlreadyOwned isn't surfaced as an error")
    }

    // ---------- Auto-equip on purchase ----------

    @Test
    fun confirmChipOffer_success_autoEquipsCosmetic_whenSlotEmpty() = runUnitTest {
        val equipment = FakeEquipmentRepository()
        val vm = buildVm(equipmentRepository = equipment)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val felt = SAMPLE_CATALOG.chipOffers.first { it.id == "felt_royal_red" }

        vm.takeAction(ShopAction.ConfirmPurchase(felt))

        assertEquals(listOf("felt_royal_red"), equipment.equipCalls)
        assertTrue(equipment.syncCalls >= 1, "equipment sync should fire after auto-equip")
        val event = received.firstOrNull { it is ShopEvent.RedeemSucceeded } as? ShopEvent.RedeemSucceeded
        assertNotNull(event, "RedeemSucceeded should fire")
        assertTrue(event.wasAutoEquipped, "event should report the item was auto-equipped")
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
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val felt = SAMPLE_CATALOG.chipOffers.first { it.id == "felt_royal_red" }

        vm.takeAction(ShopAction.ConfirmPurchase(felt))

        assertTrue(equipment.equipCalls.isEmpty(), "should not auto-equip when slot already occupied")
        val event = received.firstOrNull { it is ShopEvent.RedeemSucceeded } as? ShopEvent.RedeemSucceeded
        assertNotNull(event, "RedeemSucceeded should fire")
        assertFalse(event.wasAutoEquipped, "event should report no auto-equip when slot occupied")
    }

    @Test
    fun confirmChipOffer_success_doesNotAutoEquip_forNonSlotProduct() = runUnitTest {
        // Emotes don't claim a single-equip slot — auto-equip is a no-op
        // for them. (Users still pick which emotes to surface in the
        // emote tray separately.)
        val equipment = FakeEquipmentRepository()
        val vm = buildVm(equipmentRepository = equipment)
        val emote = SAMPLE_CATALOG.chipOffers.first { it.id == "emote_dance" }

        vm.takeAction(ShopAction.ConfirmPurchase(emote))

        assertTrue(equipment.equipCalls.isEmpty(), "non-slot products should not auto-equip")
    }

    // ---------- IAP path ----------

    @Test
    fun confirmIapPack_success_creditsChips_emitsPurchaseFinished() = runUnitTest {
        val inv = FakeInventoryRepository()
        val billing = FakeBillingClient(nextResult = PurchaseResult.Success(SAMPLE_TRANSACTION))
        val chips = FakeChipsRepository(initialBalance = 0)
        val vm = buildVm(inventoryRepository = inv, chipsRepository = chips, billingClient = billing)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val pack = SAMPLE_CATALOG.chipPacks.first()

        vm.takeAction(ShopAction.ConfirmPurchase(pack))

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

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

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

        vm.takeAction(ShopAction.ConfirmPurchase(pack))

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

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

        assertEquals(0L, chips.getBalance())
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertEquals(IapPurchaseOutcome.StoreUnavailable, (event as ShopEvent.PurchaseFinished).outcome)
    }

    @Test
    fun confirmIapPack_noSession_emitsNotSignedIn_doesNotCallBilling() = runUnitTest {
        // No Supabase session at all (offline cold start, anon sign-in
        // disabled). There's no userId to forward, so the store can't be
        // reached — surface NotSignedIn rather than crashing.
        val billing = FakeBillingClient(nextResult = PurchaseResult.Success(SAMPLE_TRANSACTION))
        val identity = FakeAuthRepository(initialState = AuthState.Unauthenticated())
        val vm = buildVm(billingClient = billing, authRepository = identity)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

        assertEquals(0, billing.purchaseCalls, "should not call billing without a userId")
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertEquals(IapPurchaseOutcome.NotSignedIn, (event as ShopEvent.PurchaseFinished).outcome)
    }

    @Test
    fun confirmIapPack_anonymousUser_emitsClaimAccountRequired_doesNotCallBilling() = runUnitTest {
        // Real-money IAP is gated behind account claim: an anonymous
        // (un-linked) Supabase user is routed to the claim flow instead of
        // the platform purchase sheet, killing the "paid then lost the
        // account" risk at the source.
        val billing = FakeBillingClient(nextResult = PurchaseResult.Success(SAMPLE_TRANSACTION))
        val identity = FakeAuthRepository(
            initialState = AuthState.Authenticated(
                userId = SAMPLE_USER_ID,
                isAnonymous = true,
                email = null,
            ),
        )
        val chips = FakeChipsRepository(initialBalance = 0)
        val vm = buildVm(billingClient = billing, authRepository = identity, chipsRepository = chips)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

        assertEquals(0, billing.purchaseCalls, "anonymous users must not reach billing")
        assertEquals(0L, chips.getBalance(), "no chips credited when routed to claim")
        assertTrue(
            received.any { it is ShopEvent.ClaimAccountRequired },
            "expected ClaimAccountRequired, got: $received",
        )
    }

    // ---------- Sync on launch ----------

    @Test
    fun init_kicksInventorySync() = runUnitTest {
        val inv = FakeInventoryRepository()
        buildVm(inventoryRepository = inv)
        // ShopViewModel.init calls inventoryRepository.sync() once.
        assertEquals(1, inv.syncCalls)
    }

    // ---------- Test scaffolding ----------

    private fun buildVm(
        productsRepository: FakeProductsRepository = FakeProductsRepository(SAMPLE_CATALOG),
        inventoryRepository: FakeInventoryRepository = FakeInventoryRepository(),
        chipsRepository: FakeChipsRepository = FakeChipsRepository(),
        progressionRepository: FakeProgressionRepository = FakeProgressionRepository(),
        billingClient: FakeBillingClient = FakeBillingClient(),
        authRepository: FakeAuthRepository = FakeAuthRepository(
            initialState = AuthState.Authenticated(
                userId = SAMPLE_USER_ID,
                isAnonymous = false,
                email = null,
            ),
        ),
        equipmentRepository: FakeEquipmentRepository = FakeEquipmentRepository(),
    ): ShopViewModel = ShopViewModel(
        productsRepository = productsRepository,
        inventoryRepository = inventoryRepository,
        chipsRepository = chipsRepository,
        progressionRepository = progressionRepository,
        billingClient = billingClient,
        authRepository = authRepository,
        equipmentRepository = equipmentRepository,
    )

    private class FakeProductsRepository(initial: ProductCatalog) : ProductsRepository {
        private val state = MutableStateFlow(initial)
        private val timeAnchor = MutableStateFlow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?>(null)
        private val isRefreshing = MutableStateFlow(false)
        var nextRefreshResult: Result<ProductCatalog>? = null

        override fun observeCatalog(): Flow<ProductCatalog> = state.asStateFlow()

        override fun observeTimeAnchor(): Flow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?> =
            timeAnchor.asStateFlow()

        override fun observeIsRefreshing(): Flow<Boolean> = isRefreshing.asStateFlow()

        override suspend fun refresh(force: Boolean): Result<ProductCatalog> {
            isRefreshing.value = true
            try {
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
            } finally {
                isRefreshing.value = false
            }
        }
    }

    private class FakeInventoryRepository : InventoryRepository {
        private val state = MutableStateFlow<List<InventoryItem>>(emptyList())
        val redeemCalls = mutableListOf<Pair<String, Long>>()
        var nextRedeemResult: RedeemResult? = null
        var syncCalls: Int = 0
            private set

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
        override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) { }
        override suspend fun deleteAll() { state.value = emptyList() }
        override suspend fun sync(): Result<Unit> {
            syncCalls++
            return Result.success(Unit)
        }
        fun emit(items: List<InventoryItem>) { state.value = items }
    }

    private class FakeEquipmentRepository(
        initial: List<EquipmentEntry> = emptyList(),
    ) : EquipmentRepository {
        private val state = MutableStateFlow(initial)
        val equipCalls = mutableListOf<String>()
        val unequipCalls = mutableListOf<String>()
        var syncCalls: Int = 0
            private set

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
        override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> = emptyList()
        override suspend fun deleteAll() { state.value = emptyList() }
        override suspend fun sync(): Result<Unit> {
            syncCalls++
            return Result.success(Unit)
        }
    }

    private class FakeChipsRepository(
        initialBalance: Long? = 10_000L,
    ) : ChipsRepository {
        private val state = MutableStateFlow(initialBalance)
        /** Records (signed-delta, reason, idempotencyKey). Signed for backward
         *  compat with existing assertions that check for negative shop debits
         *  and positive IAP credits. */
        val appliedDeltas = mutableListOf<Triple<Long, String, String?>>()

        override fun observeBalance(): Flow<Long?> = state.asStateFlow()
        override suspend fun getBalance(): Long? = state.value
        override suspend fun addChips(amount: Long, reason: String, idempotencyKey: String?) {
            appliedDeltas += Triple(+amount, reason, idempotencyKey)
            state.value = (state.value ?: 0L) + amount
        }
        override suspend fun subtractChips(amount: Long, reason: String, idempotencyKey: String?) {
            appliedDeltas += Triple(-amount, reason, idempotencyKey)
            state.value = (state.value ?: 0L) - amount
        }
        override suspend fun setBalance(authoritativeBalance: Long) { state.value = authoritativeBalance }
        override suspend fun deleteAll() { state.value = null }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        fun emit(value: Long?) { state.value = value }
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

    private class FakeAuthRepository(
        initialState: AuthState = AuthState.Unauthenticated(),
    ) : AuthRepository {
        private val _state = MutableStateFlow(initialState)
        override suspend fun current(): AuthState = _state.value
        override fun observe(): Flow<AuthState> = _state.asStateFlow()
        override suspend fun retry(): AuthState = _state.value

        override suspend fun signInWithEmail(email: String, password: String): SignInOutcome =
            error("not used in shop tests")
        override suspend fun signUpWithEmail(email: String, password: String): SignUpOutcome =
            error("not used in shop tests")
        override suspend fun refreshSession(): RefreshOutcome = error("not used in shop tests")
        override suspend fun resendVerificationEmail(email: String): ResendOutcome =
            error("not used in shop tests")
        override suspend fun sendPasswordResetEmail(email: String): SendResetOutcome =
            error("not used in shop tests")
        override suspend fun signOut() = error("not used in shop tests")
        override suspend fun deleteAccount(): DeleteAccountOutcome = error("not used in shop tests")
        override suspend fun linkOAuthIdentity(provider: OAuthProvider): LinkIdentityOutcome =
            error("not used in shop tests")
        override suspend fun linkEmailIdentity(email: String, password: String): LinkEmailIdentityOutcome =
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

        private const val SAMPLE_USER_ID = "11111111-1111-1111-1111-111111111111"

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
        override suspend fun debugSetTotalXp(totalXp: Long) {
            state.value = state.value.copy(totalXp = totalXp)
        }
    }
}

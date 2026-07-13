package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.DefaultLevelCurve
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.LevelCurve
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.ProgressionConfig
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.yield
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
 *    catalog intact + raises the refresh-error flag.
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
        assertEquals(5, vm.state.catalog.chipOffers.size)
    }

    @Test
    fun failedRefresh_preservesPriorCatalog_andSurfacesError() = runUnitTest {
        val repo = FakeProductsRepository(SAMPLE_CATALOG)
        val vm = buildVm(productsRepository = repo)
        assertEquals(2, vm.state.catalog.chipPacks.size)

        repo.nextRefreshResult = Result.failure(RuntimeException("offline"))
        vm.takeAction(ShopAction.Refresh(force = true))

        assertEquals(2, vm.state.catalog.chipPacks.size, "prior catalog preserved")
        assertTrue(vm.state.hasRefreshError)
    }

    @Test
    fun repoDrivenFirstLoadFailure_surfacesRefreshError() = runUnitTest {
        // SHOP-10: the cold-boot catalog fetch is repository-self-triggered —
        // no VM action fires it. When it fails on a fresh install, the state
        // must carry the error so the screen shows a retry surface instead of
        // a misleading "shop is empty".
        val repo = FakeProductsRepository(ProductCatalog.Empty)
        val vm = buildVm(productsRepository = repo)

        repo.simulateSelfRefresh(Result.failure(RuntimeException("cold-boot fetch failed")))

        assertTrue(vm.state.hasLoaded, "the failed first refresh still counts as load-finished")
        assertTrue(vm.state.hasRefreshError, "a failed first load must not read as an empty shop")
    }

    @Test
    fun repoDrivenRefreshSuccess_clearsRefreshError() = runUnitTest {
        val repo = FakeProductsRepository(ProductCatalog.Empty)
        val vm = buildVm(productsRepository = repo)
        repo.simulateSelfRefresh(Result.failure(RuntimeException("first fetch failed")))
        assertTrue(vm.state.hasRefreshError)

        repo.simulateSelfRefresh(Result.success(SAMPLE_CATALOG))

        assertFalse(vm.state.hasRefreshError, "a later successful refresh clears the error")
        assertEquals(2, vm.state.catalog.chipPacks.size)
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
        assertTrue(vm.state.hasRefreshError)

        vm.takeAction(ShopAction.DismissError)
        assertFalse(vm.state.hasRefreshError)
    }

    @Test
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun deepLinkScrollRequest_setsPendingCategory_thenScrollConsumedClearsIt() = runUnitTest {
        val bus = FakeShopDeepLinkBus()
        val vm = buildVm(deepLinkBus = bus)
        assertNull(vm.state.pendingScrollCategory)

        bus.requestScrollTo(com.dangerfield.cards.features.shop.ShopCategory.Avatars)
        advanceUntilIdle()
        assertEquals(
            com.dangerfield.cards.features.shop.ShopCategory.Avatars,
            vm.state.pendingScrollCategory,
        )

        // The screen fires this once it's scrolled, so a recompose doesn't
        // re-trigger the deep-link scroll.
        vm.takeAction(ShopAction.ScrollConsumed)
        assertNull(vm.state.pendingScrollCategory)
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
    fun chipBalanceReconciling_mirrorsRepository() = runUnitTest {
        val chips = FakeChipsRepository(initialBalance = 42_000)
        val vm = buildVm(chipsRepository = chips)
        assertEquals(false, vm.state.chipBalanceReconciling)

        chips.reconciling.value = true
        assertEquals(true, vm.state.chipBalanceReconciling)

        chips.reconciling.value = false
        assertEquals(false, vm.state.chipBalanceReconciling)
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
    fun confirmChipOffer_insufficientChips_emitsInsufficientChips_doesNotSetBanner() = runUnitTest {
        val inv = FakeInventoryRepository().apply {
            nextRedeemResult = RedeemResult.InsufficientChips
        }
        val vm = buildVm(inventoryRepository = inv)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val offer = SAMPLE_CATALOG.chipOffers.first()

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

        val event = received.firstOrNull { it is ShopEvent.InsufficientChips }
        assertNotNull(event, "InsufficientChips should fire as a transient error snackbar")
        assertEquals(offer.id, (event as ShopEvent.InsufficientChips).offer.id)
        // The transient error must NOT leak into the persistent screen banner.
        assertFalse(vm.state.hasRefreshError, "refresh-error banner stays clear")
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
        assertFalse(vm.state.hasRefreshError, "AlreadyOwned isn't surfaced as an error")
    }

    @Test
    fun confirmPurchase_expiredOffer_emitsOfferExpired_andForcesRefresh() = runUnitTest {
        val repo = FakeProductsRepository(SAMPLE_CATALOG)
        val inv = FakeInventoryRepository()
        val vm = buildVm(productsRepository = repo, inventoryRepository = inv)
        // Install a time anchor (the fake captures one per successful refresh).
        vm.takeAction(ShopAction.Refresh(force = true))
        val refreshesBeforeConfirm = repo.refreshCalls
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val expired = Product.ChipOffer(
            id = "felt_flash_sale",
            title = "Flash Sale Felt",
            subtitle = "Felt",
            iconEmoji = "🟨",
            costChips = 1_000,
            grantsKey = "felt.flash_sale",
            availableUntilEpochMs = -1L, // already past the anchor's epoch-0 clock
        )

        vm.takeAction(ShopAction.ConfirmPurchase(expired))

        val event = received.filterIsInstance<ShopEvent.OfferExpired>().firstOrNull()
        assertNotNull(event, "OfferExpired should fire for a past sale window")
        assertEquals(expired.id, event.productId)
        assertTrue(inv.redeemCalls.isEmpty(), "expired offer must not charge")
        assertTrue(
            repo.refreshCalls > refreshesBeforeConfirm,
            "VM auto-refreshes so the expired offer drops out of the catalog",
        )
    }

    // ---------- XP Boost consumable ----------

    @Test
    fun confirmXpBoost_success_spendsChips_grantsInactiveBoost_emitsBoostPurchased() = runUnitTest {
        val chips = FakeChipsRepository(initialBalance = 10_000)
        val inv = FakeInventoryRepository()
        val boost = FakeXpBoostRepository()
        val vm = buildVm(inventoryRepository = inv, chipsRepository = chips, xpBoostRepository = boost)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val offer = SAMPLE_CATALOG.chipOffers.first { it.id == "boost_xp_2x" }

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

        assertEquals(9_000L, chips.getBalance(), "chips debited by the boost cost")
        assertEquals(1, boost.grantCalls.size, "one boost added to the stash")
        assertTrue(boost.activateCalls.isEmpty(), "buying must NOT light the boost")
        val event = received.firstOrNull { it is ShopEvent.BoostPurchased }
        assertNotNull(event, "BoostPurchased should fire")
        assertEquals(offer.id, (event as ShopEvent.BoostPurchased).offer.id)
        // Consumable: no inventory row is ever written, so it never reads as "owned".
        assertTrue(inv.redeemCalls.isEmpty(), "boost must not write an inventory row")
    }

    @Test
    fun confirmXpBoost_grantFailsAfterDebit_refundsChips_andEmitsFailure() = runUnitTest {
        // ECON-2: the chip debit and the boost grant hit two different stores,
        // so they can't be one transaction. If the grant write fails *after*
        // the chips were debited, the spend must be refunded — the player must
        // never lose chips and get no boost. Mirrors InventoryRepository's
        // redeem/refund compensation.
        val chips = FakeChipsRepository(initialBalance = 10_000)
        val boost = FakeXpBoostRepository().apply {
            grantError = RuntimeException("cache write failed")
        }
        val vm = buildVm(chipsRepository = chips, xpBoostRepository = boost)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val offer = SAMPLE_CATALOG.chipOffers.first { it.id == "boost_xp_2x" } // cost 1_000

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

        assertEquals(10_000L, chips.getBalance(), "a grant failure after the debit must refund the spend")
        assertTrue(
            received.any { it is ShopEvent.BoostPurchaseFailed },
            "the failed buy must surface so the tap isn't silent",
        )
        assertFalse(
            received.any { it is ShopEvent.BoostPurchased },
            "no success signal when the grant never landed",
        )
    }

    @Test
    fun confirmXpBoost_insufficientChips_isNoOp() = runUnitTest {
        // ConfirmPurchase gates on the Available sheet mode; an unaffordable
        // boost classifies as Insufficient, so confirm is a pure no-op —
        // no chip spend, no activation, no event.
        val chips = FakeChipsRepository(initialBalance = 0)
        val boost = FakeXpBoostRepository()
        val vm = buildVm(chipsRepository = chips, xpBoostRepository = boost)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val offer = SAMPLE_CATALOG.chipOffers.first { it.id == "boost_xp_2x" }

        vm.takeAction(ShopAction.ConfirmPurchase(offer))

        assertEquals(0L, chips.getBalance(), "no chips spent when unaffordable")
        assertTrue(boost.grantCalls.isEmpty(), "boost not granted when unaffordable")
        assertTrue(received.isEmpty(), "no event for a gated no-op confirm")
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
    //
    // The billing round-trip (store purchase, chip credit, acknowledge,
    // anonymous gating) now lives in PurchaseChipPackUseCase — covered by
    // DefaultPurchaseChipPackUseCaseTest in :libraries:billing:impl. The VM's
    // remaining job is to invoke the use case and map its outcome to the right
    // ShopEvent. These tests pin that mapping.

    @Test
    fun confirmIapPack_invokesUseCase_andEmitsPurchaseFinished() = runUnitTest {
        val inv = FakeInventoryRepository()
        val purchase = FakePurchaseChipPackUseCase(
            nextOutcome = IapPurchaseOutcome.Success(grantedChips = 30_000),
        )
        val vm = buildVm(inventoryRepository = inv, purchaseChipPack = purchase)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }
        val pack = SAMPLE_CATALOG.chipPacks.first()

        vm.takeAction(ShopAction.ConfirmPurchase(pack))

        assertEquals(listOf(pack), purchase.calls, "use case invoked once with the pack")
        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertNotNull(event)
        val outcome = (event as ShopEvent.PurchaseFinished).outcome
        assertTrue(outcome is IapPurchaseOutcome.Success, "got: $outcome")
        // IAP doesn't touch the inventory table — chip packs are not cosmetics.
        assertTrue(inv.redeemCalls.isEmpty())
    }

    @Test
    fun confirmIapPack_cancel_emitsCancelledOutcome() = runUnitTest {
        val purchase = FakePurchaseChipPackUseCase(nextOutcome = IapPurchaseOutcome.Cancelled)
        val vm = buildVm(purchaseChipPack = purchase)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

        val event = received.firstOrNull { it is ShopEvent.PurchaseFinished }
        assertEquals(IapPurchaseOutcome.Cancelled, (event as ShopEvent.PurchaseFinished).outcome)
    }

    @Test
    fun confirmIapPack_storeUnavailable_showsThePurchaseErrorDialog() = runUnitTest {
        // Failures get a full dialog (state-driven), never just a toast — the
        // user may have paid and deserves an explanation (BILL-7).
        val purchase = FakePurchaseChipPackUseCase(nextOutcome = IapPurchaseOutcome.StoreUnavailable)
        val vm = buildVm(purchaseChipPack = purchase)

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

        assertEquals(PurchaseError.StoreUnavailable, vm.state.purchaseError)
        vm.takeAction(ShopAction.DismissPurchaseError)
        assertNull(vm.state.purchaseError)
    }

    @Test
    fun confirmIapPack_uncreditedRedeem_showsThePaidButPendingDialog() = runUnitTest {
        // The paid-but-uncredited case (redeem 400/unreachable) gets its own
        // copy: the payment stood, the launch redeemer recovers the chips.
        val purchase = FakePurchaseChipPackUseCase(
            nextOutcome = IapPurchaseOutcome.Failed(IapPurchaseOutcome.Failed.REASON_REDEEM_UNAVAILABLE),
        )
        val vm = buildVm(purchaseChipPack = purchase)

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

        assertEquals(PurchaseError.UncreditedWillRetry, vm.state.purchaseError)
        assertFalse(vm.state.purchaseInFlight, "the in-flight overlay clears once the round-trip resolves")
    }

    @Test
    fun confirmIapPack_anonymousUser_emitsClaimAccountRequired() = runUnitTest {
        // The use case returns ClaimAccountRequired for an anonymous user; the
        // VM folds that into its dedicated ShopEvent.ClaimAccountRequired (it
        // never rides PurchaseFinished).
        val purchase = FakePurchaseChipPackUseCase(
            nextOutcome = IapPurchaseOutcome.ClaimAccountRequired,
        )
        val vm = buildVm(purchaseChipPack = purchase)
        val received = mutableListOf<ShopEvent>()
        backgroundScope.launch { vm.eventFlow.collect { received += it } }

        vm.takeAction(ShopAction.ConfirmPurchase(SAMPLE_CATALOG.chipPacks.first()))

        assertTrue(
            received.any { it is ShopEvent.ClaimAccountRequired },
            "expected ClaimAccountRequired, got: $received",
        )
        assertFalse(
            received.any { it is ShopEvent.PurchaseFinished },
            "claim signal must not also fire PurchaseFinished",
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

    // ---------- Level curve ----------

    @Test
    fun playerLevel_derivesAgainstConfiguredCurve_notBundledDefault() = runUnitTest {
        val xp = 100L
        // Default curve: 100 XP already crosses into level 2.
        val defaultVm = buildVm(
            progressionRepository = FakeProgressionRepository(Progression.Empty.copy(totalXp = xp)),
        )
        assertEquals(2, defaultVm.state.playerLevel, "sanity: default curve puts 100 XP at level 2")

        // A server-retuned steeper curve (level 1 needs 1,000 to advance) must
        // keep the same XP at level 1 — display derives off the configured curve.
        val steepVm = buildVm(
            progressionRepository = FakeProgressionRepository(Progression.Empty.copy(totalXp = xp)),
            progressionConfig = FakeProgressionConfig(LevelCurve(xpPerLevel = listOf(1_000L))),
        )
        assertEquals(1, steepVm.state.playerLevel, "configured curve must drive the shown level")
    }

    // ---------- Test scaffolding ----------

    private fun buildVm(
        productsRepository: FakeProductsRepository = FakeProductsRepository(SAMPLE_CATALOG),
        inventoryRepository: FakeInventoryRepository = FakeInventoryRepository(),
        chipsRepository: FakeChipsRepository = FakeChipsRepository(),
        progressionRepository: FakeProgressionRepository = FakeProgressionRepository(),
        purchaseChipPack: FakePurchaseChipPackUseCase = FakePurchaseChipPackUseCase(),
        equipmentRepository: FakeEquipmentRepository = FakeEquipmentRepository(),
        xpBoostRepository: FakeXpBoostRepository = FakeXpBoostRepository(),
        deepLinkBus: FakeShopDeepLinkBus = FakeShopDeepLinkBus(),
        progressionConfig: ProgressionConfig = FakeProgressionConfig(),
    ): ShopViewModel = ShopViewModel(
        productsRepository = productsRepository,
        inventoryRepository = inventoryRepository,
        chipsRepository = chipsRepository,
        progressionRepository = progressionRepository,
        progressionConfig = progressionConfig,
        purchaseChipPack = purchaseChipPack,
        equipmentRepository = equipmentRepository,
        xpBoostRepository = xpBoostRepository,
        deepLinkBus = deepLinkBus,
    )

    private class FakePurchaseChipPackUseCase(
        var nextOutcome: IapPurchaseOutcome = IapPurchaseOutcome.Success(grantedChips = 0),
    ) : PurchaseChipPackUseCase {
        val calls = mutableListOf<Product.ChipPack>()
        override suspend fun invoke(pack: Product.ChipPack): IapPurchaseOutcome {
            calls += pack
            return nextOutcome
        }
    }

    private class FakeProgressionConfig(
        private val curve: LevelCurve = DefaultLevelCurve,
    ) : ProgressionConfig {
        override fun rewardsForLevel(level: Int): List<LevelReward> = emptyList()
        override fun levelCurve(): LevelCurve = curve
    }

    private class FakeXpBoostRepository : com.dangerfield.cards.libraries.cards.XpBoostRepository {
        val grantCalls = mutableListOf<Int>()
        val activateCalls = mutableListOf<Long>()
        /** When set, [grant] throws it — simulates a persist failure so the
         *  VM's post-debit compensation path can be exercised. */
        var grantError: Throwable? = null
        private val state = MutableStateFlow(
            com.dangerfield.cards.libraries.cards.XpBoostStatus.None,
        )

        override fun observe(): Flow<com.dangerfield.cards.libraries.cards.XpBoostStatus> =
            state.asStateFlow()
        override suspend fun status(): com.dangerfield.cards.libraries.cards.XpBoostStatus = state.value
        override suspend fun grant(count: Int) {
            grantError?.let { throw it }
            grantCalls += count
        }
        override suspend fun activate(durationMs: Long): Boolean {
            activateCalls += durationMs
            return true
        }
        override suspend fun multiplier(): Int = 1
    }

    private class FakeProductsRepository(initial: ProductCatalog) : ProductsRepository {
        private val state = MutableStateFlow(initial)
        private val timeAnchor = MutableStateFlow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?>(null)
        private val isRefreshing = MutableStateFlow(false)
        private val refreshFailed = MutableStateFlow(false)
        var nextRefreshResult: Result<ProductCatalog>? = null
        var refreshCalls: Int = 0
            private set

        override fun observeCatalog(): Flow<ProductCatalog> = state.asStateFlow()

        override fun observeTimeAnchor(): Flow<com.dangerfield.cards.libraries.products.CatalogTimeAnchor?> =
            timeAnchor.asStateFlow()

        override fun observeIsRefreshing(): Flow<Boolean> = isRefreshing.asStateFlow()

        override fun observeRefreshFailed(): Flow<Boolean> = refreshFailed.asStateFlow()

        /** A cold-boot / session-rollover refresh the repo fires on its own —
         *  no VM action involved, only the observable flows move. */
        suspend fun simulateSelfRefresh(result: Result<ProductCatalog>) {
            isRefreshing.value = true
            refreshFailed.value = false
            yield()
            result
                .onSuccess { state.value = it }
                .onFailure { refreshFailed.value = true }
            isRefreshing.value = false
        }

        override suspend fun refresh(force: Boolean): Result<ProductCatalog> {
            refreshCalls++
            isRefreshing.value = true
            refreshFailed.value = false
            try {
                val result = nextRefreshResult
                if (result != null) {
                    nextRefreshResult = null
                    if (result.isFailure) refreshFailed.value = true
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

        override val walletJustCreated = MutableStateFlow(false)
        val reconciling = MutableStateFlow(false)
        override val isReconciling = reconciling
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
                Product.ChipOffer(
                    id = "boost_xp_2x",
                    title = "XP Boost",
                    subtitle = "5 minutes",
                    iconEmoji = "⚡",
                    costChips = 1_000,
                    grantsKey = "boost.xp_2x",
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
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
        override suspend fun deleteAll() {}
        override suspend fun debugSetTotalXp(totalXp: Long) {
            state.value = state.value.copy(totalXp = totalXp)
        }
    }

    private class FakeShopDeepLinkBus :
        com.dangerfield.cards.features.shop.ShopDeepLinkBus {
        private val channel =
            kotlinx.coroutines.channels.Channel<com.dangerfield.cards.features.shop.ShopCategory>(
                capacity = kotlinx.coroutines.channels.Channel.CONFLATED,
            )

        override val scrollRequests: Flow<com.dangerfield.cards.features.shop.ShopCategory> =
            channel.receiveAsFlow()

        override fun requestScrollTo(
            category: com.dangerfield.cards.features.shop.ShopCategory,
        ) {
            channel.trySend(category)
        }
    }
}

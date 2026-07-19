package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.billing.PurchaseChipPackUseCase
import com.dangerfield.cards.libraries.billing.PurchaseHistoryItem
import com.dangerfield.cards.libraries.billing.PurchaseHistoryOutcome
import com.dangerfield.cards.libraries.billing.PurchaseHistoryRepository
import com.dangerfield.cards.libraries.billing.PurchaseStatus
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.products.Product
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PurchaseHistoryViewModelTest : CoroutineTest() {

    @Test
    fun load_populatesTheList() = runUnitTest {
        val vm = build(history = FakeHistory(PurchaseHistoryOutcome.Loaded(listOf(item("t1")))))
        vm.takeAction(PurchaseHistoryAction.Load)
        assertEquals(1, vm.state.items.size)
        assertFalse(vm.state.loading)
        assertFalse(vm.state.unavailable)
    }

    @Test
    fun load_emptyResult_isNotFlaggedUnavailable() = runUnitTest {
        val vm = build(history = FakeHistory(PurchaseHistoryOutcome.Loaded(emptyList())))
        vm.takeAction(PurchaseHistoryAction.Load)
        assertTrue(vm.state.items.isEmpty())
        assertFalse(vm.state.unavailable, "an empty list is 'no purchases', not an error")
    }

    @Test
    fun load_unavailableWithNothingCached_flagsUnavailable() = runUnitTest {
        val vm = build(history = FakeHistory(PurchaseHistoryOutcome.Unavailable))
        vm.takeAction(PurchaseHistoryAction.Load)
        assertTrue(vm.state.unavailable, "a failed load with nothing to show is an error surface, not empty")
    }

    @Test
    fun sync_drainsOutstanding_thenReloads() = runUnitTest {
        val drain = FakeUseCase()
        val history = FakeHistory(PurchaseHistoryOutcome.Loaded(listOf(item("t1"))))
        val vm = build(history = history, useCase = drain)

        vm.takeAction(PurchaseHistoryAction.Sync)

        assertEquals(1, drain.redeemOutstandingCalls, "sync re-runs the outstanding-purchase drain")
        assertEquals(1, vm.state.items.size, "the list reloads after the drain")
        assertFalse(vm.state.syncing)
    }

    private fun build(
        history: PurchaseHistoryRepository = FakeHistory(PurchaseHistoryOutcome.Loaded(emptyList())),
        useCase: PurchaseChipPackUseCase = FakeUseCase(),
    ) = PurchaseHistoryViewModel(history, useCase)

    private fun item(id: String) = PurchaseHistoryItem(
        transactionId = id,
        productId = "chip_pack_medium",
        title = "Tall Stack",
        iconEmoji = "💰",
        chips = 30_000,
        status = PurchaseStatus.Added,
        dateEpochMs = 0,
    )

    private class FakeHistory(private val outcome: PurchaseHistoryOutcome) : PurchaseHistoryRepository {
        override suspend fun history(): PurchaseHistoryOutcome = outcome
    }

    private class FakeUseCase : PurchaseChipPackUseCase {
        var redeemOutstandingCalls = 0
            private set
        override suspend fun invoke(pack: Product.ChipPack): IapPurchaseOutcome = error("unused")
        override suspend fun redeemOutstanding() {
            redeemOutstandingCalls += 1
        }
    }
}

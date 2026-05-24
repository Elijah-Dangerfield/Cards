package com.dangerfield.cards.features.profile.impl.items

import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.products.CatalogTimeAnchor
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [MyItemsViewModel]'s single-equip-per-slot invariant: equipping a
 * new felt / card back / title retires any previously-equipped item in
 * the same slot, so the play screen's "first equipped wins" rendering
 * doesn't silently override the user's new pick.
 */
class MyItemsViewModelTest : CoroutineTest() {

    @Test
    fun toggleEquipped_equippingFelt_unequipsOtherFelt() = runUnitTest {
        val equipment = FakeEquipmentRepository(
            initial = listOf(
                equippedEntry("felt_midnight_blue"),
            ),
        )
        val vm = buildVm(equipmentRepository = equipment)

        vm.takeAction(MyItemsAction.ToggleEquipped("felt_royal_red"))

        assertEquals(listOf("felt_midnight_blue"), equipment.unequipCalls)
        assertEquals(listOf("felt_royal_red"), equipment.equipCalls)
    }

    @Test
    fun toggleEquipped_equippingCardBack_doesNotTouchFelt() = runUnitTest {
        // Different slot — switching a card back leaves the felt alone.
        val equipment = FakeEquipmentRepository(
            initial = listOf(equippedEntry("felt_midnight_blue")),
        )
        val vm = buildVm(equipmentRepository = equipment)

        vm.takeAction(MyItemsAction.ToggleEquipped("cardback_marble"))

        assertTrue(equipment.unequipCalls.isEmpty(), "should not unequip across slots")
        assertEquals(listOf("cardback_marble"), equipment.equipCalls)
    }

    @Test
    fun toggleEquipped_unequippingOwnSlot_doesNotInvokeOtherUnequips() = runUnitTest {
        // The user is unequipping (toggling off) — no slot reshuffle.
        val equipment = FakeEquipmentRepository(
            initial = listOf(equippedEntry("felt_royal_red")),
        )
        val vm = buildVm(equipmentRepository = equipment)

        vm.takeAction(MyItemsAction.ToggleEquipped("felt_royal_red"))

        assertEquals(listOf("felt_royal_red"), equipment.unequipCalls)
        assertTrue(equipment.equipCalls.isEmpty())
    }

    @Test
    fun ownedItems_propagatesAcquisitionSourceFromInventory() = runUnitTest {
        val inventory = FakeInventoryRepository(
            initial = listOf(
                inventoryItem("ck.gold", AcquisitionSource.Purchased),
                inventoryItem("ck.legendary", AcquisitionSource.Earned),
            ),
        )
        val vm = buildVm(inventoryRepository = inventory)

        val owned = vm.state.ownedItems.associateBy { it.productId }
        assertEquals(AcquisitionSource.Purchased, owned.getValue("ck.gold").acquisitionSource)
        assertEquals(AcquisitionSource.Earned, owned.getValue("ck.legendary").acquisitionSource)
    }

    @Test
    fun toggleEquipped_nonSlotProduct_doesNotTouchOtherEquipment() = runUnitTest {
        // Tools / avatar packs / emote packs don't claim a slot — the
        // user can have several on at once.
        val equipment = FakeEquipmentRepository(
            initial = listOf(equippedEntry("tool_win_odds")),
        )
        val vm = buildVm(equipmentRepository = equipment)

        vm.takeAction(MyItemsAction.ToggleEquipped("emote_dance"))

        assertTrue(equipment.unequipCalls.isEmpty(), "non-slot toggle should not unequip anything else")
        assertEquals(listOf("emote_dance"), equipment.equipCalls)
    }

    private fun buildVm(
        inventoryRepository: FakeInventoryRepository = FakeInventoryRepository(),
        productsRepository: FakeProductsRepository = FakeProductsRepository(),
        equipmentRepository: FakeEquipmentRepository = FakeEquipmentRepository(),
    ): MyItemsViewModel = MyItemsViewModel(
        inventoryRepository = inventoryRepository,
        productsRepository = productsRepository,
        equipmentRepository = equipmentRepository,
    )

    private fun equippedEntry(productId: String): EquipmentEntry = EquipmentEntry(
        productId = productId,
        isEquipped = true,
        syncState = EquipmentSyncState.Synced,
        updatedAtEpochMs = 0L,
    )

    private fun inventoryItem(
        productId: String,
        source: AcquisitionSource,
    ): InventoryItem = InventoryItem(
        productId = productId,
        state = PurchaseState.Confirmed,
        purchasedAtEpochMs = 0L,
        costChipsAtPurchase = 0L,
        acquisitionSource = source,
    )

    private class FakeInventoryRepository(
        initial: List<InventoryItem> = emptyList(),
    ) : InventoryRepository {
        private val state = MutableStateFlow(initial)
        override fun observeInventory(): Flow<List<InventoryItem>> = state.asStateFlow()
        override suspend fun getInventory(): List<InventoryItem> = state.value
        override suspend fun redeemChipOffer(productId: String, costChips: Long): RedeemResult =
            error("not used")
        override suspend fun markConfirmed(productIds: Collection<String>) { }
        override suspend fun revertPurchase(productId: String) { }
        override suspend fun applyServerSnapshot(authoritative: List<InventoryItem>) { }
        override suspend fun deleteAll() { }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }

    private class FakeProductsRepository : ProductsRepository {
        private val catalog = MutableStateFlow(ProductCatalog.Empty)
        private val anchor = MutableStateFlow<CatalogTimeAnchor?>(null)
        override fun observeCatalog(): Flow<ProductCatalog> = catalog.asStateFlow()
        override fun observeTimeAnchor(): Flow<CatalogTimeAnchor?> = anchor.asStateFlow()
        override suspend fun refresh(): Result<ProductCatalog> = Result.success(catalog.value)
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
            state.value = state.value.map {
                if (it.productId == productId) it.copy(isEquipped = false) else it
            }
            return EquipmentToggleResult.Success
        }
        override suspend fun applyServerSnapshot(authoritative: List<EquipmentEntry>) { }
        override suspend fun dropOrphanEquipment(ownedProductIds: Set<String>): List<String> = emptyList()
        override suspend fun deleteAll() { state.value = emptyList() }
        override suspend fun sync(): Result<Unit> = Result.success(Unit)
    }
}

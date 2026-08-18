package com.dangerfield.cards.features.profile.impl.items

import com.dangerfield.cards.libraries.cards.AcquisitionSource
import com.dangerfield.cards.libraries.cards.CosmeticTier
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.EquipmentSyncState
import com.dangerfield.cards.libraries.cards.EquipmentToggleResult
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.PurchaseState
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.flowroutines.testing.CoroutineTest
import com.dangerfield.cards.libraries.identity.profile.AvatarPackOutcome
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.identity.profile.UpdateProfileOutcome
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
    fun ownedItems_threadsCosmeticTier_fromEarnableMap() = runUnitTest {
        // Items present in EarnableCosmetics inherit their tier; items
        // outside it (chip packs, shop-only cosmetics) come through with
        // null. Drives the EARN_ONLY vs EARN_OR_BUY badge dispatch in
        // OwnedItemRow.
        val inventory = FakeInventoryRepository(
            initial = listOf(
                inventoryItem("title_pot_magnet", AcquisitionSource.Earned),
                inventoryItem("chips_1000", AcquisitionSource.Purchased),
            ),
        )
        val vm = buildVm(inventoryRepository = inventory)

        val owned = vm.state.ownedItems.associateBy { it.productId }
        assertEquals(CosmeticTier.EARN_ONLY, owned.getValue("title_pot_magnet").tier)
        assertEquals(null, owned.getValue("chips_1000").tier)
    }

    @Test
    fun ownedItems_defaultCosmetic_isEquippedWhenSlotHasNoExplicitEquip() = runUnitTest {
        // Fresh account: defaults are seeded into inventory but the server
        // never writes an equipment row for them, so equippedIds is empty.
        // The items grid must still mark them equipped.
        val inventory = FakeInventoryRepository(
            initial = listOf(
                inventoryItem("felt_default", AcquisitionSource.Earned),
                inventoryItem("cardback_default", AcquisitionSource.Earned),
            ),
        )
        val vm = buildVm(inventoryRepository = inventory)

        val owned = vm.state.ownedItems.associateBy { it.productId }
        assertTrue(owned.getValue("felt_default").isEquipped)
        assertTrue(owned.getValue("cardback_default").isEquipped)
    }

    @Test
    fun ownedItems_defaultCosmetic_isNotEquippedWhenSlotHasExplicitEquip() = runUnitTest {
        // The user equipped a non-default felt: the default felt loses the
        // badge, but the default card back (its slot untouched) keeps it.
        val inventory = FakeInventoryRepository(
            initial = listOf(
                inventoryItem("felt_default", AcquisitionSource.Earned),
                inventoryItem("felt_royal_red", AcquisitionSource.Purchased),
                inventoryItem("cardback_default", AcquisitionSource.Earned),
            ),
        )
        val equipment = FakeEquipmentRepository(
            initial = listOf(equippedEntry("felt_royal_red")),
        )
        val vm = buildVm(inventoryRepository = inventory, equipmentRepository = equipment)

        val owned = vm.state.ownedItems.associateBy { it.productId }
        assertTrue(owned.getValue("felt_royal_red").isEquipped)
        assertTrue(!owned.getValue("felt_default").isEquipped, "explicit felt equip retires the default felt badge")
        assertTrue(owned.getValue("cardback_default").isEquipped, "untouched slot keeps its default equipped")
    }

    @Test
    fun ownedItems_delistedSlotCosmetic_staysEquippable() = runUnitTest {
        // The catalog dropped this table theme (server pulled it in V64/V70)
        // but the user still owns it. Ownership is permanent, so the row must
        // keep its Equip button — otherwise a user who unequips it can never
        // put it back on, breaking the Terms' "existing owners keep what
        // they've purchased" promise.
        val inventory = FakeInventoryRepository(
            initial = listOf(
                inventoryItem("table_sunset", AcquisitionSource.Purchased),
            ),
        )
        // Empty catalog — the product row is gone.
        val vm = buildVm(inventoryRepository = inventory)

        val owned = vm.state.ownedItems.associateBy { it.productId }
        assertTrue(
            owned.getValue("table_sunset").isEquippable,
            "a delisted-but-owned slot cosmetic must remain equippable",
        )
    }

    @Test
    fun ownedItems_delistedPack_staysNonEquippable() = runUnitTest {
        // A pack (avatar / emote) has no slot and nothing to equip. If its
        // catalog row is gone it must NOT sprout a dead Equip button.
        val inventory = FakeInventoryRepository(
            initial = listOf(
                inventoryItem("avatars_animals", AcquisitionSource.Purchased),
            ),
        )
        val vm = buildVm(inventoryRepository = inventory)

        val owned = vm.state.ownedItems.associateBy { it.productId }
        assertTrue(
            !owned.getValue("avatars_animals").isEquippable,
            "a delisted pack has no slot and stays non-equippable",
        )
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
        profileRepository: FakeProfileRepository = FakeProfileRepository(),
    ): MyItemsViewModel = MyItemsViewModel(
        inventoryRepository = inventoryRepository,
        productsRepository = productsRepository,
        equipmentRepository = equipmentRepository,
        profileRepository = profileRepository,
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
        private val refreshing = MutableStateFlow(false)
        override fun observeCatalog(): Flow<ProductCatalog> = catalog.asStateFlow()
        override fun observeTimeAnchor(): Flow<CatalogTimeAnchor?> = anchor.asStateFlow()
        override fun observeIsRefreshing(): Flow<Boolean> = refreshing.asStateFlow()
        override fun observeRefreshFailed(): Flow<Boolean> = MutableStateFlow(false).asStateFlow()

        override fun observeChipPacksUnavailable(): Flow<Boolean> = MutableStateFlow(false).asStateFlow()
        override suspend fun refresh(force: Boolean): Result<ProductCatalog> = Result.success(catalog.value)
    }

    private class FakeProfileRepository : ProfileRepository {
        override suspend fun current(): Profile = Profile.Fallback(id = "test")
        override fun observe(): Flow<Profile> = MutableStateFlow(Profile.Fallback(id = "test")).asStateFlow()
        override suspend fun update(
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("not used")
        // No avatar packs in unit tests — pack-emoji enrichment just stays empty.
        override suspend fun fetchAvatarPack(): AvatarPackOutcome =
            AvatarPackOutcome.Success(packs = emptyList())
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

package com.dangerfield.cards.features.profile.impl.items

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.EquipmentEntry
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Drives the My Items screen. Joins three live sources:
 *  - inventory (what the user owns)
 *  - catalog (display metadata: title, description, emoji)
 *  - equipment (which of the owned items are equipped + sync state)
 *
 * Optimistic toggles: tapping equip/unequip writes Pending to the local
 * Room table immediately (the flow re-emits, UI re-renders without a
 * round-trip), then fires [EquipmentRepository.sync] as a best-effort
 * background reconcile. If the user is offline, the row stays Pending
 * and the next bootstrap cycle picks it up.
 *
 * Catalog refresh is kicked at init so visually-stale tiles get the
 * latest server descriptions on entry. Equipment sync is kicked too in
 * case the bootstrapper hasn't fired this session (e.g. the user
 * navigated here from a deep link before any AppEvent landed).
 *
 * Items that the user owns but for which the catalog dropped the entry
 * (server pulled the product down after purchase) still render with
 * fallback copy — ownership is permanent regardless of catalog drift.
 */
@Inject
class MyItemsViewModel(
    private val inventoryRepository: InventoryRepository,
    private val productsRepository: ProductsRepository,
    private val equipmentRepository: EquipmentRepository,
) : SEAViewModel<MyItemsState, MyItemsEvent, MyItemsAction>(initialStateArg = MyItemsState()) {

    private val logger = KLog.withTag("MyItemsViewModel")

    init {
        viewModelScope.launch {
            inventoryRepository.observeInventory().collect { items ->
                takeAction(MyItemsAction.InventoryChanged(items))
            }
        }
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(MyItemsAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch {
            equipmentRepository.observeEquipped().collect { entries ->
                takeAction(MyItemsAction.EquipmentChanged(entries))
            }
        }
        // Best-effort fetches on entry. Both gracefully no-op when offline.
        viewModelScope.launch { productsRepository.refresh() }
        viewModelScope.launch { equipmentRepository.sync() }
    }

    override suspend fun handleAction(action: MyItemsAction) {
        when (action) {
            is MyItemsAction.InventoryChanged -> action.updateState {
                it.copy(inventory = action.items)
            }
            is MyItemsAction.CatalogChanged -> action.updateState {
                it.copy(catalog = action.catalog)
            }
            is MyItemsAction.EquipmentChanged -> action.updateState {
                it.copy(
                    equippedIds = action.entries
                        .filter { entry -> entry.isEquipped }
                        .map { entry -> entry.productId }
                        .toSet(),
                )
            }

            is MyItemsAction.ToggleEquipped -> action.run {
                val currentlyEquipped = action.productId in state.equippedIds
                // Optimistic: the equipment flow re-emits within the same
                // frame so we never need to manually flip UI state here.
                if (currentlyEquipped) {
                    equipmentRepository.unequip(action.productId)
                } else {
                    // Single-equip per slot: if the user picks a new felt /
                    // card back / title, retire any previously-equipped item
                    // in the same slot first. The rendering layer used to
                    // pick the first-equipped one, so leaving both equipped
                    // meant the new pick wouldn't take effect.
                    val slot = cosmeticSlotFor(action.productId)
                    if (slot != null) {
                        state.equippedIds
                            .filter { it != action.productId && cosmeticSlotFor(it) == slot }
                            .forEach { equipmentRepository.unequip(it) }
                    }
                    equipmentRepository.equip(action.productId)
                }
                // Fire-and-forget reconcile so Pending → Synced flips
                // before the user even sees the row settle.
                viewModelScope.launch {
                    equipmentRepository.sync().onFailure {
                        logger.w(it) { "Sync after toggle failed; row stays Pending until next launch." }
                    }
                }
            }
        }
    }
}

/**
 * One row on the My Items list. Derived from the inventory ∩ catalog join
 * inside [MyItemsState.ownedItems]. If the catalog dropped the product
 * after purchase, fields fall back to friendly placeholders so the user
 * isn't confronted with a blank row.
 */
data class OwnedItem(
    val productId: String,
    val title: String,
    val subtitle: String,
    val description: String?,
    val iconEmoji: String,
    val isEquipped: Boolean,
    /**
     * Mirrored from the catalog `Product.isEquippable`. False for unlock-
     * style products (avatar packs, emote packs) — the row still renders,
     * but the Equip/Unequip button is suppressed. Falls back to false
     * when the catalog entry is missing so a stale-catalog row doesn't
     * silently show an Equip button that doesn't do anything.
     */
    val isEquippable: Boolean,
)

data class MyItemsState(
    val inventory: List<InventoryItem> = emptyList(),
    val catalog: ProductCatalog = ProductCatalog.Empty,
    val equippedIds: Set<String> = emptySet(),
) {
    /** Owned items, newest purchase first, with catalog metadata folded in. */
    val ownedItems: List<OwnedItem>
        get() {
            val productsById = (catalog.chipOffers + catalog.chipPacks).associateBy { it.id }
            return inventory.map { item ->
                val product = productsById[item.productId]
                OwnedItem(
                    productId = item.productId,
                    title = product?.title ?: prettifyMissingId(item.productId),
                    subtitle = product?.subtitle ?: "Owned item",
                    description = (product as? Product.ChipOffer)?.description,
                    iconEmoji = product?.iconEmoji ?: "🎁",
                    isEquipped = item.productId in equippedIds,
                    isEquippable = product?.isEquippable ?: false,
                )
            }
        }

    private fun prettifyMissingId(productId: String): String =
        productId.substringAfterLast('.', productId)
            .replace('_', ' ')
            .replaceFirstChar { it.titlecase() }
}

sealed interface MyItemsEvent

sealed interface MyItemsAction {
    data class InventoryChanged(val items: List<InventoryItem>) : MyItemsAction
    data class CatalogChanged(val catalog: ProductCatalog) : MyItemsAction
    data class EquipmentChanged(val entries: List<EquipmentEntry>) : MyItemsAction
    data class ToggleEquipped(val productId: String) : MyItemsAction
}

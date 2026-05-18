package com.dangerfield.cards.features.shop.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.InventoryItem
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.InventorySyncService
import com.dangerfield.cards.libraries.cards.RedeemResult
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Backs the shop screen.
 *
 * Three streams are wired on init:
 *  - Catalog (server-driven what's-for-sale)
 *  - Chip balance (drives can-afford gating + "you have / after" preview)
 *  - Inventory (drives the "OWNED" badge on chip offers)
 *
 * Catalog refresh is kicked at init + on pull-to-refresh. Inventory sync
 * (locally-pending → server-confirmed) is also kicked at init — once auth
 * lands and the server tracks per-user state, that becomes the
 * reconciliation pass.
 *
 * Optimistic redemption flow ([ShopAction.ConfirmPendingPurchase] for a
 * chip-funded offer):
 *  1. Pre-check chip balance — if insufficient, surface error and close
 *     the sheet without mutating anything. (UI also disables the button
 *     when balance < cost so this path is defensive, not primary.)
 *  2. Call [InventoryRepository.redeemChipOffer] — it atomically inserts
 *     the inventory row as Pending and deducts chips.
 *  3. Close the sheet, emit a confirmation event for the UI to celebrate
 *     (toast, haptic, whatever).
 *  4. Best-effort fire [InventorySyncService.sync] in the background so
 *     the row flips Pending → Confirmed before the user closes the app.
 *
 * IAP packs ([ShopAction.ConfirmPendingPurchase] for a chip pack) emit a
 * [ShopEvent.LaunchPurchase] for the future PurchaseCoordinator to pick
 * up; nothing local changes until the platform store confirms.
 */
class ShopViewModel @Inject constructor(
    private val productsRepository: ProductsRepository,
    private val inventoryRepository: InventoryRepository,
    private val inventorySyncService: InventorySyncService,
    private val chipsRepository: ChipsRepository,
) : SEAViewModel<ShopState, ShopEvent, ShopAction>(initialStateArg = ShopState()) {

    private val logger = KLog.withTag("ShopViewModel")

    init {
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(ShopAction.CatalogChanged(catalog))
            }
        }
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(ShopAction.ChipsChanged(balance))
            }
        }
        viewModelScope.launch {
            inventoryRepository.observeInventory().collect { inventory ->
                takeAction(ShopAction.InventoryChanged(inventory))
            }
        }
        // Kick off catalog refresh + inventory sync (these run concurrently).
        takeAction(ShopAction.Refresh)
        viewModelScope.launch { inventorySyncService.sync() }
    }

    override suspend fun handleAction(action: ShopAction) {
        when (action) {
            is ShopAction.Refresh -> {
                action.updateState { it.copy(isRefreshing = true, errorMessage = null) }
                viewModelScope.launch {
                    val result = productsRepository.refresh()
                    result.onFailure { failure ->
                        logger.w(failure) { "Catalog refresh failed" }
                        takeAction(ShopAction.RefreshFailed(failure.message ?: "Couldn't load shop"))
                    }
                    takeAction(ShopAction.RefreshFinished)
                }
            }
            is ShopAction.RefreshFinished -> action.updateState {
                it.copy(isRefreshing = false, hasLoaded = true)
            }
            is ShopAction.RefreshFailed -> action.updateState {
                it.copy(errorMessage = action.message)
            }
            is ShopAction.CatalogChanged -> action.updateState {
                it.copy(catalog = action.catalog)
            }
            is ShopAction.ChipsChanged -> action.updateState {
                it.copy(chipBalance = action.balance)
            }
            is ShopAction.InventoryChanged -> action.updateState {
                it.copy(
                    inventory = action.inventory,
                    ownedProductIds = action.inventory.map { item -> item.productId }.toSet(),
                )
            }
            is ShopAction.DismissError -> action.updateState {
                it.copy(errorMessage = null)
            }

            // ---- Purchase intent flow ----

            is ShopAction.RequestPurchase -> {
                // Don't open the sheet for items the user already owns —
                // pure defensive (UI also gates this), but covers any race.
                if (state.ownsProduct(action.product.id)) return
                action.updateState { it.copy(pendingPurchase = PendingPurchase.from(action.product)) }
            }
            is ShopAction.DismissPendingPurchase -> action.updateState {
                it.copy(pendingPurchase = null)
            }
            is ShopAction.ConfirmPendingPurchase -> {
                val pending = state.pendingPurchase ?: return
                when (pending) {
                    is PendingPurchase.IapPack -> {
                        // Hand off to the platform-IAP coordinator. Sheet
                        // closes; no local mutation yet — that happens on
                        // store callback (later chunk).
                        action.updateState { it.copy(pendingPurchase = null) }
                        sendEvent(ShopEvent.LaunchPurchase(pending.product))
                    }
                    is PendingPurchase.ChipOffer -> {
                        confirmChipOfferRedeem(pending.product)
                    }
                }
            }
        }
    }

    private suspend fun confirmChipOfferRedeem(offer: Product.ChipOffer) {
        // Close the sheet first — feedback should be instant; the actual
        // optimistic write lands in the same frame on UnconfinedTestDispatcher
        // and a thread-or-two later in production.
        val action = ShopAction.ConfirmPendingPurchase
        action.updateState { it.copy(pendingPurchase = null) }

        val result = inventoryRepository.redeemChipOffer(
            productId = offer.id,
            costChips = offer.costChips,
        )
        when (result) {
            is RedeemResult.Success -> {
                sendEvent(ShopEvent.RedeemSucceeded(offer))
                // Fire-and-forget server reconcile so the Pending row flips
                // to Confirmed before the user closes the app. Failures
                // here are non-fatal — next launch retries.
                viewModelScope.launch { inventorySyncService.sync() }
            }
            is RedeemResult.InsufficientChips -> {
                // UI's affordance check should prevent this, but if a race
                // sneaks through (balance changed between sheet-open and
                // confirm) we surface it as an error toast.
                action.updateState {
                    it.copy(errorMessage = "Not enough chips for ${offer.title}.")
                }
            }
            is RedeemResult.AlreadyOwned -> {
                // Idempotent: tell the user it's already in their library
                // (without bothering them too much).
                sendEvent(ShopEvent.AlreadyOwned(offer))
            }
        }
    }
}

// ---------- MVI types ----------

data class ShopState(
    val catalog: ProductCatalog = ProductCatalog.Empty,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val chipBalance: Long = ChipsRepository.STARTING_GRANT,
    val errorMessage: String? = null,
    val inventory: List<InventoryItem> = emptyList(),
    /** Quick-lookup set of product ids the user owns. Updated alongside
     *  [inventory] so screen code doesn't re-derive it on every recompose. */
    val ownedProductIds: Set<String> = emptySet(),
    /** Non-null while the purchase confirmation sheet is up. */
    val pendingPurchase: PendingPurchase? = null,
) {
    fun ownsProduct(productId: String): Boolean = productId in ownedProductIds

    /**
     * Can the user afford the offer? Used by the screen to disable the
     * row + by the VM as a final guard before mutating state.
     */
    fun canAfford(offer: Product.ChipOffer): Boolean = chipBalance >= offer.costChips
}

/**
 * One in-flight purchase, kept on state so the bottom sheet renders.
 *
 * Two variants because the flow downstream is different: IAP packs need
 * the platform billing system to confirm, chip offers complete locally and
 * optimistically.
 */
sealed interface PendingPurchase {
    data class IapPack(val product: Product.ChipPack) : PendingPurchase
    data class ChipOffer(val product: Product.ChipOffer) : PendingPurchase

    companion object {
        fun from(product: Product): PendingPurchase = when (product) {
            is Product.ChipPack -> IapPack(product)
            is Product.ChipOffer -> ChipOffer(product)
        }
    }
}

sealed interface ShopAction {
    /** Pull-to-refresh / initial load. */
    data object Refresh : ShopAction
    data object RefreshFinished : ShopAction
    data class RefreshFailed(val message: String) : ShopAction
    data class CatalogChanged(val catalog: ProductCatalog) : ShopAction
    data class ChipsChanged(val balance: Long) : ShopAction
    data class InventoryChanged(val inventory: List<InventoryItem>) : ShopAction
    data object DismissError : ShopAction

    /** User tapped a product row → open the purchase confirmation sheet. */
    data class RequestPurchase(val product: Product) : ShopAction

    /** User tapped the confirm CTA inside the sheet → commit. */
    data object ConfirmPendingPurchase : ShopAction

    /** User dismissed the sheet (cancel, swipe-down, back press, tap outside). */
    data object DismissPendingPurchase : ShopAction
}

sealed interface ShopEvent {
    /** Fire for IAP packs — handler launches the platform billing flow. */
    data class LaunchPurchase(val product: Product.ChipPack) : ShopEvent

    /** Chip-funded redemption confirmed. Screen plays a celebration cue. */
    data class RedeemSucceeded(val offer: Product.ChipOffer) : ShopEvent

    /** Idempotent re-redeem — user tried to buy something they already own. */
    data class AlreadyOwned(val offer: Product.ChipOffer) : ShopEvent
}

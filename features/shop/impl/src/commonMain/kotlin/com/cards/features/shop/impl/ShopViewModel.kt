package com.dangerfield.cards.features.shop.impl

import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.SEAViewModel
import com.dangerfield.cards.libraries.products.Product
import com.dangerfield.cards.libraries.products.ProductCatalog
import com.dangerfield.cards.libraries.products.ProductsRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject

/**
 * Backs the shop screen. Loads the product catalog from
 * [ProductsRepository], observes the chip balance from [ChipsRepository],
 * and exposes both to the screen as one [ShopState].
 *
 * Catalog lifecycle:
 *  - On init, subscribes to the cached flow (emits [ProductCatalog.Empty] until
 *    the first successful refresh) and kicks off a refresh.
 *  - Pull-to-refresh fires [ShopAction.Refresh] which re-runs the network
 *    fetch. Errors surface as the [ShopState.errorMessage] field so the
 *    screen can show a snackbar without losing the prior catalog.
 *
 * Why one combined [ShopState] field for the catalog instead of separate
 * `loading` + `data`: a screen that shows the *prior* catalog while refreshing
 * is the right UX. Separate booleans for "is refreshing" + "has loaded
 * before" + "error to surface" map directly onto the state shape below.
 */
class ShopViewModel @Inject constructor(
    private val productsRepository: ProductsRepository,
    private val chipsRepository: ChipsRepository,
) : SEAViewModel<ShopState, ShopEvent, ShopAction>(initialStateArg = ShopState()) {

    private val logger = KLog.withTag("ShopViewModel")

    init {
        // Catalog feed → state
        viewModelScope.launch {
            productsRepository.observeCatalog().collect { catalog ->
                takeAction(ShopAction.CatalogChanged(catalog))
            }
        }
        // Chip balance mirror
        viewModelScope.launch {
            chipsRepository.observeBalance().collect { balance ->
                takeAction(ShopAction.ChipsChanged(balance))
            }
        }
        // Kick the first refresh
        takeAction(ShopAction.Refresh)
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
            is ShopAction.DismissError -> action.updateState {
                it.copy(errorMessage = null)
            }
            is ShopAction.PurchaseChipPack -> {
                // Real platform-IAP flow lands in a later chunk. For now,
                // surface the intended SKU as an event so the screen can
                // show a "Coming soon" toast (and a future
                // PurchaseCoordinator picks this up to launch the platform
                // billing flow).
                sendEvent(ShopEvent.LaunchPurchase(action.product))
            }
            is ShopAction.RedeemChipOffer -> {
                Catching {
                    chipsRepository.applyDelta(-action.offer.costChips)
                    sendEvent(ShopEvent.OfferRedeemed(action.offer))
                }.onFailure { logger.w(it) { "Redeem failed for ${action.offer.id}" } }
            }
        }
    }
}

// ---------- MVI types ----------

data class ShopState(
    /** Latest catalog from the repo. Empty until first successful refresh. */
    val catalog: ProductCatalog = ProductCatalog.Empty,
    /** True while a network refresh is in flight. Prior catalog stays visible. */
    val isRefreshing: Boolean = false,
    /** True once at least one refresh has completed (success OR failure). */
    val hasLoaded: Boolean = false,
    /** User's current chip balance. */
    val chipBalance: Long = ChipsRepository.STARTING_GRANT,
    /** Latest refresh failure message; null when there's nothing to show. */
    val errorMessage: String? = null,
)

sealed interface ShopAction {
    /** User-triggered refresh (initial load + pull-to-refresh). */
    data object Refresh : ShopAction

    /** Internal — refresh completed (any outcome). */
    data object RefreshFinished : ShopAction

    /** Internal — refresh failed; pipe the message to state. */
    data class RefreshFailed(val message: String) : ShopAction

    /** Internal — repo flow emitted a new catalog. */
    data class CatalogChanged(val catalog: ProductCatalog) : ShopAction

    /** Internal — chip balance updated. */
    data class ChipsChanged(val balance: Long) : ShopAction

    /** User dismissed the error snackbar. */
    data object DismissError : ShopAction

    /** User tapped a chip pack — kick the platform-IAP flow. */
    data class PurchaseChipPack(val product: Product.ChipPack) : ShopAction

    /** User tapped a chip-purchasable offer — deduct chips, grant the item. */
    data class RedeemChipOffer(val offer: Product.ChipOffer) : ShopAction
}

sealed interface ShopEvent {
    /** Fired when the user wants to buy an IAP product — handler launches the platform billing flow. */
    data class LaunchPurchase(val product: Product.ChipPack) : ShopEvent

    /** Fired after a successful chip-funded redemption. */
    data class OfferRedeemed(val offer: Product.ChipOffer) : ShopEvent
}

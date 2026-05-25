package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.profile.FeedbackRoute
import com.dangerfield.cards.features.shop.ShopGraph
import com.dangerfield.cards.features.shop.ShopProductSheetRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.flowroutines.ObserveEvents
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.OnTabReselected
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.bottomSheet
import com.dangerfield.cards.libraries.navigation.graphScopedViewModel
import com.dangerfield.cards.libraries.navigation.navigation
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarDuration
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class ShopFeatureEntryPoint(
    private val shopVmFactory: () -> ShopViewModel,
) : FeatureEntryPoint {
    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        // Nested graph so the grid + purchase sheet share a single
        // `ShopViewModel` scoped to the graph entry (lives as long as
        // anything in the Shop tab is on the stack), instead of
        // coupling the VM lifecycle to the grid's specific entry.
        navigation<ShopGraph>(startDestination = ShopRoute()) {
            screen<ShopRoute> {
                val viewModel = router.graphScopedViewModel<ShopGraph, ShopViewModel> {
                    shopVmFactory()
                }
                val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
                val scrollState = rememberScrollState()
                val scope = rememberCoroutineScope()
                // Bottom-bar re-tap on the Shop tab → scroll the grid to top.
                router.OnTabReselected(ShopGraph) {
                    scope.launch { scrollState.animateScrollTo(0) }
                }

                viewModel.ObserveEvents { event ->
                    when (event) {
                        is ShopEvent.PurchaseFinished -> showPurchaseSnackbar(event.outcome)
                        is ShopEvent.RedeemSucceeded -> showSnackBar(
                            title = "Unlocked!",
                            message = "${event.offer.title} is yours.",
                            emoji = event.offer.iconEmoji,
                            duration = SnackbarDuration.Short,
                        )
                        is ShopEvent.AlreadyOwned -> showSnackBar(
                            title = "Already yours",
                            message = "Look for ${event.offer.title} in your items.",
                            emoji = event.offer.iconEmoji,
                            duration = SnackbarDuration.Short,
                        )
                        is ShopEvent.OfferExpired -> showSnackBar(
                            title = "Just missed it",
                            message = "That offer's window closed. Refreshed the shop.",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }

                ShopScreen(
                    state = state,
                    onAction = viewModel::takeAction,
                    onProductTap = { productId ->
                        router.navigate(ShopProductSheetRoute(productId))
                    },
                    onIdeaTap = { router.navigate(FeedbackRoute()) },
                    scrollState = scrollState,
                )
            }

            // Purchase sheet sub-route — same VM as the grid above
            // because they're in the same graph.
            bottomSheet<ShopProductSheetRoute> { backStackEntry, sheetState ->
                val route = backStackEntry.toRoute<ShopProductSheetRoute>()
                val viewModel = router.graphScopedViewModel<ShopGraph, ShopViewModel> {
                    shopVmFactory()
                }
                val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
                val product = state.catalog.findById(route.productId)

                if (product == null) {
                    // Catalog hasn't hydrated yet (cold start, deep-link
                    // arrived before refresh) OR the product genuinely
                    // isn't in the catalog (server dropped it between
                    // the caller building the link and this firing).
                    // For now, silently pop. A polished version would
                    // render a loading shell + dismiss after a timeout
                    // — small follow-up.
                    router.goBack()
                    return@bottomSheet
                }

                PurchaseConfirmSheet(
                    sheetState = sheetState,
                    product = product,
                    mode = state.sheetModeFor(product),
                    chipBalance = state.chipBalance ?: 0L,
                    timeAnchor = state.timeAnchor,
                    onConfirm = {
                        router.goBack()
                        viewModel.takeAction(ShopAction.ConfirmPurchase(product))
                    },
                    onDismiss = { router.goBack() },
                )
            }
        }
    }
}

private fun showPurchaseSnackbar(outcome: IapPurchaseOutcome) {
    when (outcome) {
        is IapPurchaseOutcome.Success -> showSnackBar(
            title = "Chips added",
            message = "+${outcome.grantedChips} chips",
            emoji = "🪙",
            duration = SnackbarDuration.Short,
        )
        is IapPurchaseOutcome.AlreadyOwned -> showSnackBar(
            title = "Restored",
            message = "+${outcome.grantedChips} chips re-credited from a prior purchase.",
            emoji = "🪙",
            duration = SnackbarDuration.Short,
        )
        IapPurchaseOutcome.Cancelled -> Unit
        IapPurchaseOutcome.StoreUnavailable -> showSnackBar(
            title = "Store unavailable",
            message = "Couldn't reach the App Store right now. Try again in a moment.",
            duration = SnackbarDuration.Short,
        )
        IapPurchaseOutcome.NotSignedIn -> showSnackBar(
            title = "Sign in first",
            message = "Purchases require a signed-in account.",
            duration = SnackbarDuration.Short,
        )
        is IapPurchaseOutcome.Failed -> showSnackBar(
            title = "Purchase failed",
            message = outcome.reason,
            duration = SnackbarDuration.Long,
        )
    }
}

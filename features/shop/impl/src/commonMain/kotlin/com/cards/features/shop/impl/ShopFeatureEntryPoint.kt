package com.dangerfield.cards.features.shop.impl

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.flowroutines.ObserveEvents
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.components.SnackbarDuration
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
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
        screen<ShopRoute> {
            val viewModel: ShopViewModel = viewModel(key = "shop") { shopVmFactory() }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value

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
            )
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

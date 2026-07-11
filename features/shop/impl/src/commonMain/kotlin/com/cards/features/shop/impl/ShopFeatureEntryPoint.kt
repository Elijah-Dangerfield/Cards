package com.dangerfield.cards.features.shop.impl

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.profile.ClaimAccountRoute
import com.dangerfield.cards.features.profile.FeedbackRoute
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.shop.ShopGraph
import com.dangerfield.cards.features.shop.ShopProductSheetRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.billing.IapPurchaseOutcome
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.ObserveEvents
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.OnTabReselected
import com.dangerfield.cards.libraries.core.LegalUrls
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.bottomSheet
import com.dangerfield.cards.libraries.navigation.graphScopedViewModel
import com.dangerfield.cards.libraries.navigation.navigation
import com.dangerfield.cards.libraries.navigation.screen
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.shop_snackbar_already_owned_message
import cards.libraries.resources.generated.resources.shop_snackbar_already_owned_title
import cards.libraries.resources.generated.resources.shop_snackbar_boost_purchased_action
import cards.libraries.resources.generated.resources.shop_snackbar_boost_purchased_message
import cards.libraries.resources.generated.resources.shop_snackbar_boost_purchased_title
import cards.libraries.resources.generated.resources.shop_snackbar_chips_added_message
import cards.libraries.resources.generated.resources.shop_snackbar_chips_added_title
import cards.libraries.resources.generated.resources.shop_snackbar_chips_restored_message
import cards.libraries.resources.generated.resources.shop_snackbar_chips_restored_title
import cards.libraries.resources.generated.resources.shop_snackbar_claim_required_action
import cards.libraries.resources.generated.resources.shop_snackbar_claim_required_message
import cards.libraries.resources.generated.resources.shop_snackbar_claim_required_title
import cards.libraries.resources.generated.resources.shop_snackbar_insufficient_chips_message
import cards.libraries.resources.generated.resources.shop_snackbar_insufficient_chips_title
import cards.libraries.resources.generated.resources.shop_snackbar_not_signed_in_message
import cards.libraries.resources.generated.resources.shop_snackbar_not_signed_in_title
import cards.libraries.resources.generated.resources.shop_snackbar_offer_expired_message
import cards.libraries.resources.generated.resources.shop_snackbar_offer_expired_title
import cards.libraries.resources.generated.resources.shop_snackbar_purchase_failed_title
import cards.libraries.resources.generated.resources.shop_snackbar_redeem_auto_equipped_message
import cards.libraries.resources.generated.resources.shop_snackbar_redeem_auto_equipped_title
import cards.libraries.resources.generated.resources.shop_snackbar_redeem_succeeded_action_equip
import cards.libraries.resources.generated.resources.shop_snackbar_redeem_succeeded_action_view
import cards.libraries.resources.generated.resources.shop_snackbar_redeem_succeeded_message
import cards.libraries.resources.generated.resources.shop_snackbar_redeem_succeeded_title
import cards.libraries.resources.generated.resources.shop_snackbar_store_unavailable_message
import cards.libraries.resources.generated.resources.shop_snackbar_store_unavailable_title
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarDuration
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarLevel
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import org.jetbrains.compose.resources.getString
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class ShopFeatureEntryPoint(
    private val shopVmFactory: () -> ShopViewModel,
    private val appCache: AppCache,
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
                // Screen-entry, not VM init: the graph-scoped VM outlives
                // individual visits, and the funnel counts storefront views.
                LaunchedEffect(Unit) {
                    KLog.logEvent("shop.viewed")
                }
                // Bottom-bar re-tap on the Shop tab → scroll the grid to top.
                router.OnTabReselected(ShopGraph) {
                    scope.launch { scrollState.animateScrollTo(0) }
                }

                viewModel.ObserveEvents { event ->
                    when (event) {
                        is ShopEvent.PurchaseFinished -> showPurchaseSnackbar(event.outcome)
                        is ShopEvent.RedeemSucceeded -> {
                            // The snackbar action jumps to the Profile tab and
                            // spotlights the new item on the bookshelf. When the
                            // slot was free we auto-equipped it, so we tell the
                            // user it's already equipped and give a plain "View"
                            // jump; otherwise equippable cosmetics get an "Equip"
                            // label (they can equip from the item's detail sheet)
                            // and unlock-style products (avatar / emote packs) get
                            // a "View" jump.
                            val title: String
                            val message: String
                            val actionLabel: String
                            if (event.wasAutoEquipped) {
                                title = getString(Res.string.shop_snackbar_redeem_auto_equipped_title)
                                message = getString(
                                    Res.string.shop_snackbar_redeem_auto_equipped_message,
                                    event.offer.title,
                                )
                                actionLabel =
                                    getString(Res.string.shop_snackbar_redeem_succeeded_action_view)
                            } else {
                                title = getString(Res.string.shop_snackbar_redeem_succeeded_title)
                                message = getString(
                                    Res.string.shop_snackbar_redeem_succeeded_message,
                                    event.offer.title,
                                )
                                actionLabel = getString(
                                    if (event.offer.isEquippable) {
                                        Res.string.shop_snackbar_redeem_succeeded_action_equip
                                    } else {
                                        Res.string.shop_snackbar_redeem_succeeded_action_view
                                    },
                                )
                            }
                            showSnackBar(
                                title = title,
                                message = message,
                                emoji = event.offer.iconEmoji,
                                duration = SnackbarDuration.Short,
                                actionLabel = actionLabel,
                                onAction = {
                                    // Tab-root args don't survive restoreState,
                                    // so carry the "spotlight this" signal through
                                    // the shared cache, then switch to Profile.
                                    scope.launch {
                                        appCache.update {
                                            it.copy(pendingProfileHighlight = event.offer.id)
                                        }
                                        router.switchTab(ProfileRoute())
                                    }
                                },
                            )
                        }
                        is ShopEvent.BoostPurchased -> showSnackBar(
                            title = getString(Res.string.shop_snackbar_boost_purchased_title),
                            message = getString(Res.string.shop_snackbar_boost_purchased_message),
                            emoji = event.offer.iconEmoji,
                            duration = SnackbarDuration.Short,
                            // It's inactive in the stash now — the action jumps to
                            // Profile where the boost banner's Activate button lights it.
                            actionLabel = getString(Res.string.shop_snackbar_boost_purchased_action),
                            onAction = { scope.launch { router.switchTab(ProfileRoute()) } },
                        )
                        is ShopEvent.AlreadyOwned -> showSnackBar(
                            title = getString(Res.string.shop_snackbar_already_owned_title),
                            message = getString(Res.string.shop_snackbar_already_owned_message, event.offer.title),
                            emoji = event.offer.iconEmoji,
                            duration = SnackbarDuration.Short,
                        )
                        is ShopEvent.OfferExpired -> showSnackBar(
                            title = getString(Res.string.shop_snackbar_offer_expired_title),
                            message = getString(Res.string.shop_snackbar_offer_expired_message),
                            duration = SnackbarDuration.Short,
                        )
                        is ShopEvent.InsufficientChips -> showSnackBar(
                            title = getString(Res.string.shop_snackbar_insufficient_chips_title),
                            message = getString(
                                Res.string.shop_snackbar_insufficient_chips_message,
                                event.offer.title,
                            ),
                            level = SnackbarLevel.Error,
                            duration = SnackbarDuration.Short,
                        )
                        ShopEvent.ClaimAccountRequired -> showSnackBar(
                            title = getString(Res.string.shop_snackbar_claim_required_title),
                            message = getString(Res.string.shop_snackbar_claim_required_message),
                            level = SnackbarLevel.Error,
                            actionLabel = getString(Res.string.shop_snackbar_claim_required_action),
                            duration = SnackbarDuration.Long,
                            onAction = { router.navigate(ClaimAccountRoute()) },
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
                    onOpenResponsiblePlay = { router.openWebLink(LegalUrls.RESPONSIBLE_PLAY) },
                )
            }
        }
    }
}

private suspend fun showPurchaseSnackbar(outcome: IapPurchaseOutcome) {
    when (outcome) {
        is IapPurchaseOutcome.Success -> showSnackBar(
            title = getString(Res.string.shop_snackbar_chips_added_title),
            message = getString(
                Res.string.shop_snackbar_chips_added_message,
                formatThousands(outcome.grantedChips),
            ),
            emoji = "🪙",
            duration = SnackbarDuration.Short,
        )
        is IapPurchaseOutcome.AlreadyOwned -> showSnackBar(
            title = getString(Res.string.shop_snackbar_chips_restored_title),
            message = getString(
                Res.string.shop_snackbar_chips_restored_message,
                formatThousands(outcome.grantedChips),
            ),
            emoji = "🪙",
            duration = SnackbarDuration.Short,
        )
        IapPurchaseOutcome.Cancelled -> Unit
        // The shop maps this outcome to ShopEvent.ClaimAccountRequired before it
        // reaches here (it never rides PurchaseFinished), so this branch only
        // exists to keep the `when` exhaustive over the shared outcome type.
        IapPurchaseOutcome.ClaimAccountRequired -> Unit
        IapPurchaseOutcome.StoreUnavailable -> showSnackBar(
            title = getString(Res.string.shop_snackbar_store_unavailable_title),
            message = getString(Res.string.shop_snackbar_store_unavailable_message),
            level = SnackbarLevel.Error,
            duration = SnackbarDuration.Short,
        )
        IapPurchaseOutcome.NotSignedIn -> showSnackBar(
            title = getString(Res.string.shop_snackbar_not_signed_in_title),
            message = getString(Res.string.shop_snackbar_not_signed_in_message),
            level = SnackbarLevel.Error,
            duration = SnackbarDuration.Short,
        )
        is IapPurchaseOutcome.Failed -> showSnackBar(
            title = getString(Res.string.shop_snackbar_purchase_failed_title),
            message = outcome.reason,
            level = SnackbarLevel.Error,
            duration = SnackbarDuration.Long,
        )
    }
}

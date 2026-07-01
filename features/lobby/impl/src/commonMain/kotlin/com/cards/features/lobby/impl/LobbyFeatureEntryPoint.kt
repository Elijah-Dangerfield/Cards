package com.dangerfield.cards.features.lobby.impl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.lobby.PrivateCreateRoute
import com.dangerfield.cards.features.lobby.PrivateJoinRoute
import com.dangerfield.cards.features.lobby.RoomInvite
import com.dangerfield.cards.features.room.PlayMultiplayerRoute
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.cards.CosmeticSlot
import com.dangerfield.cards.libraries.cards.EquipmentRepository
import com.dangerfield.cards.libraries.cards.InventoryRepository
import com.dangerfield.cards.libraries.cards.cosmeticSlotFor
import com.dangerfield.cards.libraries.cards.equippedTableCosmetics
import com.dangerfield.cards.libraries.products.ProductsRepository
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.routeDeepLink
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarDuration
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import kotlinx.coroutines.flow.combine
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class LobbyFeatureEntryPoint(
    private val viewModelFactory: (prefilledCode: String?, autoCreate: Boolean, maxSeats: Int?, buyIn: Long?, open: Boolean, feltProductId: String?, cardBackProductId: String?) -> LobbyViewModel,
    private val privateJoinViewModelFactory: (rejectedCode: String?) -> PrivateJoinViewModel,
    private val chipsRepository: ChipsRepository,
    private val inventoryRepository: InventoryRepository,
    private val productsRepository: ProductsRepository,
    private val equipmentRepository: EquipmentRepository,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        // Private create/join front-ends (SPEC §6–7). Thin screens that
        // funnel into the seated lobby below via autoCreate / prefilledCode.
        // They pop themselves on the way in so the lobby sits directly on
        // Home; leaving the lobby returns Home, not back to this setup step.
        screen<PrivateCreateRoute> {
            val chipBalance by chipsRepository.observeBalance().collectAsStateWithLifecycle(initialValue = null)
            // Owned felt + card-back options for the host's table-look picker
            // (SHOP-5). Owned-only by construction — built from inventory ∩ catalog
            // — with the equipped look resolved for the initial selection. A read
            // hiccup degrades to empty lists (picker hidden), never blocks create.
            val cosmetics by ownedTableCosmetics().collectAsStateWithLifecycle(
                initialValue = OwnedTableCosmetics(),
            )
            PrivateCreateScreen(
                chipBalance = chipBalance,
                onBack = { router.goBack() },
                onCreate = { maxPlayers, buyIn, open, feltProductId, cardBackProductId ->
                    router.batch {
                        popBackTo(PrivateCreateRoute::class, inclusive = true)
                        navigate(
                            LobbyRoute(
                                autoCreate = true,
                                maxSeats = maxPlayers,
                                buyIn = buyIn,
                                open = open,
                                feltProductId = feltProductId,
                                cardBackProductId = cardBackProductId,
                            ),
                        )
                    }
                },
                felts = cosmetics.felts,
                cardBacks = cosmetics.cardBacks,
                initialFeltProductId = cosmetics.equippedFeltProductId,
                initialCardBackProductId = cosmetics.equippedCardBackProductId,
            )
        }
        screen<PrivateJoinRoute> { backStackEntry ->
            val joinRoute = backStackEntry.toRoute<PrivateJoinRoute>()
            val viewModel: PrivateJoinViewModel = viewModel {
                privateJoinViewModelFactory(joinRoute.rejectedCode)
            }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()

            // The code is validated in place (the VM attempts the join), so the
            // only navigation off this screen is a confirmed-good code handing
            // off to the seated lobby. A bad code stays put with an inline error
            // (ROOM-5) — no route batch, no screen movement.
            LaunchedEffect(viewModel) {
                viewModel.eventFlow.collect { event ->
                    when (event) {
                        is PrivateJoinEvent.NavigateToLobby -> router.batch {
                            popBackTo(PrivateJoinRoute::class, inclusive = true)
                            navigate(LobbyRoute(prefilledCode = event.code))
                        }
                    }
                }
            }

            PrivateJoinScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
            )
        }

        // Deep-link join: `cards://join/{prefilledCode}` lands a tapped invite
        // straight in the lobby with the code pre-filled, which auto-attempts a
        // join on entry (same path as PrivateJoinScreen → LobbyRoute). The code
        // is a path segment so the shared URL reads cleanly. autoCreate/open
        // default false, so an invite link never spins up a fresh room.
        screen<LobbyRoute>(
            deepLinks = listOf(
                routeDeepLink<LobbyRoute>(basePath = "${RoomInvite.DEEP_LINK_BASE_PATH}/{prefilledCode}"),
            ),
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<LobbyRoute>()
            val viewModel: LobbyViewModel = viewModel {
                viewModelFactory(
                    route.prefilledCode,
                    route.autoCreate,
                    route.maxSeats,
                    route.buyIn,
                    route.open,
                    route.feltProductId,
                    route.cardBackProductId,
                )
            }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()

            // Route lobby events: NavigateToMultiplayer hands off to
            // the play-screen route; HostPromoted surfaces a snackbar
            // via the app-wide SnackbarHost so the remaining players
            // know who controls the next "Start hand" tap.
            LaunchedEffect(viewModel) {
                viewModel.eventFlow.collect { event ->
                    when (event) {
                        is LobbyEvent.NavigateToMultiplayer ->
                            router.navigate(PlayMultiplayerRoute(roomCode = event.roomCode))
                        is LobbyEvent.HostPromoted -> showSnackBar(
                            message = if (event.isLocalUser) "You're now the host."
                            else "${event.newHostDisplayName} is now the host.",
                            duration = SnackbarDuration.Short,
                        )
                        // The prefilled join hit an unknown room. Pop this dead
                        // lobby and land back on the code-entry screen with the bad
                        // code + inline error so the user retries in place. Match
                        // by class — this lobby was pushed with prefilledCode set,
                        // which an instance match against LobbyRoute() would silently
                        // miss, leaving a zombie lobby underneath the join screen.
                        is LobbyEvent.JoinCodeRejected -> router.batch {
                            popBackTo(LobbyRoute::class, inclusive = true)
                            navigate(PrivateJoinRoute(rejectedCode = event.code))
                        }
                    }
                }
            }

            LobbyScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
                onShareInvite = { message -> router.shareText(message) },
            )
        }
    }

    /**
     * The host's owned felt + card-back options for the create-room picker
     * (SHOP-5), plus the equipped ids for the initial selection. Owned-only by
     * construction: the felt / card-back lists are the intersection of the
     * player's inventory with the catalog's display metadata. Default cosmetics
     * (`felt_default`, `cardback_default`) are starter inventory, so each shelf
     * always has at least its Default tile. A catalog entry that's missing (a
     * stale catalog after an owned purchase) falls back to a prettified id +
     * generic glyph rather than dropping the owned option.
     */
    private fun ownedTableCosmetics(): kotlinx.coroutines.flow.Flow<OwnedTableCosmetics> = combine(
        inventoryRepository.observeInventory(),
        productsRepository.observeCatalog(),
        equipmentRepository.observeEquipped(),
    ) { inventory, catalog, equipped ->
        val productsById = catalog.chipOffers.associateBy { it.id }
        val choices = inventory.mapNotNull { item ->
            val slot = cosmeticSlotFor(item.productId)
            if (slot != CosmeticSlot.Felt && slot != CosmeticSlot.CardBack) return@mapNotNull null
            val product = productsById[item.productId]
            slot to CosmeticChoice(
                productId = item.productId,
                label = product?.title ?: prettifyCosmeticId(item.productId),
                emoji = product?.iconEmoji ?: defaultCosmeticEmoji(slot),
            )
        }
        val cosmetics = equippedTableCosmetics(equipped)
        OwnedTableCosmetics(
            felts = choices.filter { it.first == CosmeticSlot.Felt }.map { it.second },
            cardBacks = choices.filter { it.first == CosmeticSlot.CardBack }.map { it.second },
            equippedFeltProductId = cosmetics.feltProductId,
            equippedCardBackProductId = cosmetics.cardBackProductId,
        )
    }
}

/** The host's owned felt / card-back options + equipped selection for SHOP-5's picker. */
private data class OwnedTableCosmetics(
    val felts: List<CosmeticChoice> = emptyList(),
    val cardBacks: List<CosmeticChoice> = emptyList(),
    val equippedFeltProductId: String? = null,
    val equippedCardBackProductId: String? = null,
)

private fun prettifyCosmeticId(productId: String): String =
    productId.substringAfter('_', productId).replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun defaultCosmeticEmoji(slot: CosmeticSlot): String = when (slot) {
    CosmeticSlot.Felt -> "🟩"
    CosmeticSlot.CardBack -> "🂠"
    else -> "🎁"
}

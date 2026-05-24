package com.dangerfield.cards.features.home.impl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.home.WelcomeDialogRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.progression.RankDetailSheetRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.ObserveWithLifecycle
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.dialog
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.navigation.toRouteOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class HomeFeatureEntryPoint(
    private val homeViewModelFactory: () -> HomeViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<HomeRoute> {
            val viewModel: HomeViewModel = viewModel { homeViewModelFactory() }
            LaunchedEffect(Unit) {
                KLog.withTag("HomeFeatureEntryPoint").i { "Home route entered" }
            }
            ObserveWithLifecycle(viewModel.eventFlow) { event ->
                when (event) {
                    is HomeEvent.OpenWelcomeDialog -> {
                        val payload = event.payload
                        router.navigate(
                            WelcomeDialogRoute(
                                displayName = payload.displayName,
                                avatarEmoji = payload.avatarEmoji,
                                avatarBackgroundColorHex = payload.avatarBackgroundColorHex,
                                chips = payload.chips,
                            )
                        )
                    }
                }
            }
            // The bot-table setup dialog is parameterized on the difficulty
            // the user tapped so we can route to that difficulty after they
            // pick a seat count.
            var setupDifficulty by remember { mutableStateOf<String?>(null) }

            HomeScreen(
                viewModel = viewModel,
                onPlayBots = { difficulty -> setupDifficulty = difficulty },
                onTapRank = { router.navigate(RankDetailSheetRoute()) },
                onTapXp = { router.navigate(StatsRoute()) },
                onTapCash = { router.switchTab(ShopRoute()) },
                onStartGame = { router.navigate(LobbyRoute()) },
                onJoinGame = { router.navigate(LobbyRoute()) },
                onRejoinRoom = { code -> router.navigate(LobbyRoute(prefilledCode = code)) },
            )

            setupDifficulty?.let { difficulty ->
                BotTableSetupDialog(
                    difficultyLabel = difficulty,
                    onStart = { seatCount ->
                        setupDifficulty = null
                        router.navigate(
                            PlayBotsRoute(
                                difficulty = difficulty,
                                seatCount = seatCount,
                            ),
                        )
                    },
                    onDismiss = { setupDifficulty = null },
                )
            }
        }

        // Dialog destinations live on the FloatingWindow back stack — same
        // pattern as ErrorDialogRoute in libraries:navigation. The view-model
        // navigates here when its welcome gate aligns; the dialog manages
        // its own animation/dismissal lifecycle from there.
        dialog<WelcomeDialogRoute> { backStackEntry, dialogState ->
            val route = backStackEntry.toRouteOrNull<WelcomeDialogRoute>() ?: return@dialog
            WelcomeDialog(
                state = dialogState,
                displayName = route.displayName,
                avatarEmoji = route.avatarEmoji,
                avatarBackgroundColorHex = route.avatarBackgroundColorHex,
                chips = route.chips,
                onDismiss = { router.goBack() },
            )
        }
    }
}

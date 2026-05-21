package com.dangerfield.cards.features.home.impl

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.progression.RankDetailSheetRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
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
            // The bot-table setup dialog is parameterized on the difficulty
            // the user tapped so we can route to that difficulty after they
            // pick a seat count.
            var setupDifficulty by remember { mutableStateOf<String?>(null) }

            HomeScreen(
                viewModel = viewModel,
                onPlayBots = { difficulty -> setupDifficulty = difficulty },
                onTapRank = { router.navigate(RankDetailSheetRoute()) },
                onTapXp = { router.navigate(StatsRoute()) },
                onTapCash = {
                    router.navigate(
                        ShopRoute(),
                        NavigationOptions(launchSingleTop = true, clearBackStack = true),
                    )
                },
                onStartGame = { router.navigate(LobbyRoute()) },
                onJoinGame = { router.navigate(LobbyRoute()) },
                onRejoinRoom = { code -> router.navigate(LobbyRoute(prefilledCode = code)) },
            )

            setupDifficulty?.let { difficulty ->
                BotTableSetupDialog(
                    difficultyLabel = difficulty,
                    onStart = { seatCount ->
                        setupDifficulty = null
                        router.navigate(PlayBotsRoute(difficulty = difficulty, seatCount = seatCount))
                    },
                    onDismiss = { setupDifficulty = null },
                )
            }
        }
    }
}

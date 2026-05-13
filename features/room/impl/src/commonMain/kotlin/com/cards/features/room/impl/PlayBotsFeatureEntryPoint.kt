package com.dangerfield.cards.features.room.impl

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class PlayBotsFeatureEntryPoint(
    private val playBotsViewModelFactory: (difficulty: BotDifficulty, seatCount: Int) -> PlayBotsViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<PlayBotsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PlayBotsRoute>()
            val difficulty = BotDifficulty.entries.firstOrNull { it.name == route.difficulty }
                ?: BotDifficulty.Standard
            val seatCount = route.seatCount.coerceIn(2, 6)
            val viewModel: PlayBotsViewModel = viewModel(key = "play-bots-${difficulty.name}-$seatCount") {
                playBotsViewModelFactory(difficulty, seatCount)
            }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            PlayBotsScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
            )
        }
    }
}

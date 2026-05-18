package com.dangerfield.cards.features.room.impl

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.progression.XpDetailSheetRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.libraries.bots.BotDifficulty
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Wires the bot/human-agnostic [PlayPokerViewModel] + [PlayPokerScreen] into
 * the existing [PlayBotsRoute]. The route name stays — only the internal
 * VM/screen pair changed (strangler swap, Phase 0.2.g).
 *
 * For solo bot games, composes a [SoloBotsPokerSessionFactory] from the route
 * parameters (difficulty + seat count) and hands it to the VM. When MP lands,
 * a sibling entry point will build a `RemotePokerSessionFactory` from a
 * room-code route and use the same VM.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class PlayPokerFeatureEntryPoint(
    private val soloFactoryFactory: (difficulty: BotDifficulty, seatCount: Int) -> SoloBotsPokerSessionFactory,
    private val playPokerVmFactory: (sessionFactory: PokerSessionFactory) -> PlayPokerViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<PlayBotsRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PlayBotsRoute>()
            val difficulty = BotDifficulty.entries.firstOrNull { it.name == route.difficulty }
                ?: BotDifficulty.Standard
            val seatCount = route.seatCount.coerceIn(2, 6)
            val viewModel: PlayPokerViewModel = viewModel(key = "play-poker-${difficulty.name}-$seatCount") {
                val factory = soloFactoryFactory(difficulty, seatCount)
                playPokerVmFactory(factory)
            }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            PlayPokerScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
                onTapXp = { router.navigate(XpDetailSheetRoute()) },
            )
        }
    }
}

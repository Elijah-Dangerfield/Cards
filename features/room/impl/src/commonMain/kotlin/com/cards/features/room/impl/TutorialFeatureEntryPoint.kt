package com.dangerfield.cards.features.room.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.room.TutorialRoute
import com.dangerfield.cards.features.room.impl.tutorial.TutorialPokerScreen
import com.dangerfield.cards.features.room.impl.tutorial.TutorialViewModel
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Registers [TutorialRoute] into the nav graph. The route is parameterless
 * — the script lives in `TutorialScript`. Reachable from the Home tutorial
 * banner and Settings → "How to play".
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class TutorialFeatureEntryPoint(
    private val tutorialViewModelFactory: () -> TutorialViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<TutorialRoute> {
            val viewModel: TutorialViewModel = viewModel { tutorialViewModelFactory() }
            val state by viewModel.state.collectAsStateWithLifecycle()
            TutorialPokerScreen(
                state = state,
                onIntent = viewModel::submit,
                onAdvance = viewModel::advance,
                onExit = { router.goBack() },
            )
        }
    }
}

package com.dangerfield.cards.features.progression.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.progression.AchievementsRoute
import com.dangerfield.cards.features.progression.StatsExplainersSheetRoute
import com.dangerfield.cards.features.progression.StatsRoute
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
class StatsEntryPoint(
    private val viewModelFactory: () -> StatsViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<StatsRoute> {
            val viewModel: StatsViewModel = viewModel { viewModelFactory() }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()

            StatsScreen(
                state = state,
                onBack = router::goBack,
                onSeeAllAchievements = { router.navigate(AchievementsRoute()) },
                onShowExplainers = { router.navigate(StatsExplainersSheetRoute()) },
            )
        }
    }
}

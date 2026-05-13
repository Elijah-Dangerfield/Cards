package com.dangerfield.cards.features.home.impl

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.home.FeedbackRoute
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.features.home.impl.bugreport.BugReportScreen
import com.dangerfield.cards.features.home.impl.bugreport.BugReportViewModel
import com.dangerfield.cards.features.home.impl.feedback.FeedbackScreen
import com.dangerfield.cards.features.home.impl.feedback.FeedbackViewModel
import com.dangerfield.cards.features.profile.BugReportRoute
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
class HomeFeatureEntryPoint(
    private val homeViewModelFactory: () -> HomeViewModel,
    private val feedbackViewModelFactory: () -> FeedbackViewModel,
    private val bugReportViewModelFactory: (logId: String?, errorCode: Int?, contextMessage: String?) -> BugReportViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<HomeRoute> {
            val viewModel: HomeViewModel = viewModel { homeViewModelFactory() }
            HomeScreen(
                viewModel = viewModel,
                onNavigateToFeedback = { router.navigate(FeedbackRoute()) },
                onNavigateToBugReport = { router.navigate(BugReportRoute()) },
                onPlayBots = { difficulty ->
                    router.navigate(PlayBotsRoute(difficulty = difficulty, seatCount = 4))
                },
            )
        }
        
        screen<FeedbackRoute> {
            val viewModel: FeedbackViewModel = viewModel { feedbackViewModelFactory() }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            FeedbackScreen(
                state = state,
                onAction = viewModel::takeAction,
            )
        }
        
        screen<BugReportRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<BugReportRoute>()
            val viewModel: BugReportViewModel = viewModel {
                bugReportViewModelFactory(route.logId, route.errorCode, route.contextMessage)
            }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            BugReportScreen(
                state = state,
                onAction = viewModel::takeAction,
            )
        }
    }
}

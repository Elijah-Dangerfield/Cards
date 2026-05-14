package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.home.FeedbackRoute
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.home.impl.bugreport.BugReportScreen
import com.dangerfield.cards.features.home.impl.bugreport.BugReportViewModel
import com.dangerfield.cards.features.home.impl.feedback.FeedbackScreen
import com.dangerfield.cards.features.home.impl.feedback.FeedbackViewModel
import com.dangerfield.cards.features.profile.BugReportRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.NavigationOptions
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
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
            var activeDialog by remember { mutableStateOf<HomeDialog?>(null) }

            HomeScreen(
                viewModel = viewModel,
                onNavigateToFeedback = { router.navigate(FeedbackRoute()) },
                onNavigateToBugReport = { router.navigate(BugReportRoute()) },
                onPlayBots = { difficulty ->
                    router.navigate(PlayBotsRoute(difficulty = difficulty, seatCount = 4))
                },
                onTapRank = { activeDialog = HomeDialog.Rank },
                onTapCash = {
                    router.navigate(
                        ShopRoute(),
                        NavigationOptions(launchSingleTop = true, clearBackStack = true),
                    )
                },
                onStartGame = { activeDialog = HomeDialog.StartGame },
                onJoinGame = { activeDialog = HomeDialog.JoinGame },
            )

            if (activeDialog != null) {
                ComingSoonDialog(
                    dialog = activeDialog!!,
                    onDismiss = { activeDialog = null },
                )
            }
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

internal enum class HomeDialog { StartGame, JoinGame, Rank }

@Composable
private fun ComingSoonDialog(
    dialog: HomeDialog,
    onDismiss: () -> Unit,
) {
    val title = when (dialog) {
        HomeDialog.StartGame -> "Start a game with friends"
        HomeDialog.JoinGame -> "Join a friend's game"
        HomeDialog.Rank -> "Your poker rank"
    }
    val body = when (dialog) {
        HomeDialog.StartGame -> "Multiplayer is in development. Soon you'll be able to spin up a private room, share a 6-letter code, and play with friends. For now, sharpen up against the bots."
        HomeDialog.JoinGame -> "Code-based room joining lands with multiplayer. We'll let you know when it's ready."
        HomeDialog.Rank -> "Cards uses a chess-style Elo rank that updates after every hand against a real opponent. Your stats and rank history will live here once multiplayer ships."
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onDismiss) {
                Text("Got it")
            }
        }
    }
}

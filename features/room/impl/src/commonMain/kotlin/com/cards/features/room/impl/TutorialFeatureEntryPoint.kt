package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.features.room.impl.tutorial.TutorialScript

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.room.AchievementUnlockedRoute
import com.dangerfield.cards.features.room.TutorialRoute
import com.dangerfield.cards.features.room.impl.tutorial.AchievementUnlockedDialog
import com.dangerfield.cards.features.room.impl.tutorial.TutorialPokerScreen
import com.dangerfield.cards.features.room.impl.tutorial.TutorialViewModel
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.dialog
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.navigation.toRouteOrNull
import com.dangerfield.cards.libraries.ui.system.DialogIntroDelay
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Registers [TutorialRoute] into the nav graph plus the
 * [AchievementUnlockedRoute] floating-window dialog that fires when
 * the user finishes the tutorial for the first time.
 *
 * The tutorial route is parameterless; the script lives in
 * `TutorialScript`. Reachable from the Home tutorial banner and from
 * Settings → "How to play".
 *
 * The achievement-unlocked dialog is registered here for now because
 * the tutorial is its only producer. Move it to a dedicated
 * achievement feature once another caller appears (level-up,
 * bot-whisperer capstone, etc.).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class TutorialFeatureEntryPoint(
    private val tutorialViewModelFactory: () -> TutorialViewModel,
    private val appScope: AppCoroutineScope,
    private val appCache: AppCache,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<TutorialRoute> {
            val viewModel: TutorialViewModel = viewModel { tutorialViewModelFactory() }
            val state by viewModel.state.collectAsStateWithLifecycle()
            TutorialPokerScreen(
                state = state,
                onIntent = viewModel::submit,
                onAdvance = viewModel::advance,
                onGoBack = viewModel::goBack,
                onSkipBasics = viewModel::skipBasics,
                onRestartBasics = viewModel::restartBasics,
                // The Done button on the Ready screen routes here on
                // first-time completion. Pop the tutorial first so the
                // user lands on home, then delay before pushing the
                // celebration dialog — the dialog reads as a beat on
                // top of home instead of an instant overlay swap. See
                // `DialogIntroDelay`.
                //
                // The delayed navigate MUST run on the app scope, not a
                // composition-scoped `rememberCoroutineScope()`: the
                // `popBackTo` above disposes this very composition, which
                // would cancel a composition-scoped coroutine mid-`delay`
                // and swallow the navigate — the dialog would never fire.
                onAchievementUnlocked = {
                    router.popBackTo(TutorialRoute(), inclusive = true)
                    appScope.launch {
                        // Respect the Settings "achievement pop-ups" toggle — when
                        // off, completing the tutorial still banks the achievement,
                        // we just skip the celebration dialog.
                        if (!appCache.get().showAchievementPopups) return@launch
                        delay(DialogIntroDelay)
                        router.navigate(
                            AchievementUnlockedRoute(
                                achievementId = AchievementId.TUTORIAL_COMPLETE.name,
                            ),
                        )
                    }
                },
                onExit = { router.goBack() },
            )
        }

        dialog<AchievementUnlockedRoute> { backStackEntry, dialogState ->
            val route = backStackEntry.toRouteOrNull<AchievementUnlockedRoute>()
                ?: return@dialog
            AchievementUnlockedDialog(
                achievementId = route.achievementId,
                onDismiss = { router.goBack() },
                state = dialogState,
            )
        }
    }
}

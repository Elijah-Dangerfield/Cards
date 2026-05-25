package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.features.room.impl.PlayPokerAction
import com.dangerfield.cards.features.room.impl.PlayPokerScreen
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800

/**
 * Drives the **real** `PlayPokerScreen` with a fabricated [TutorialState]
 * step, overlaying a floating coach-mark banner at the top of the screen.
 *
 * - Action restriction comes for free from
 *   [TableUiState.Active.humanLegalActions] — the action bar already
 *   respects `canCall`/`canCheck`/`canRaise`.
 * - Tutorial advancement intercepts [PlayPokerAction.Submit]; the
 *   [TutorialScript] step's `advanceOn` predicate decides whether the
 *   submitted intent counts.
 * - Other [PlayPokerAction] variants (LeaveTable, BlastEmoji, etc.) are
 *   silently dropped — the tutorial doesn't fire telemetry or progression.
 */
@Composable
internal fun TutorialPokerScreen(
    state: TutorialState,
    onIntent: (com.dangerfield.cards.libraries.gameplay.PlayerIntent) -> Unit,
    onAdvance: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.completed) {
        TutorialCompletedScreen(onExit = onExit, modifier = modifier)
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        PlayPokerScreen(
            state = state.step.state,
            onAction = { action ->
                if (action is PlayPokerAction.Submit) onIntent(action.intent)
                // All other actions (LeaveTable, RequestNextHand,
                // BlastEmoji, etc.) are no-ops for the tutorial. We don't
                // dispatch into a real engine — the script controls flow.
            },
            onBack = onExit,
        )

        CoachMarkBanner(
            coach = state.step.coach,
            stepIndex = state.stepIndex,
            totalSteps = state.totalSteps,
            onAdvance = onAdvance,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                // Clears the play screen's top bar so the banner sits
                // just below the back/level pill row.
                .padding(top = 56.dp, start = 12.dp, end = 12.dp),
        )
    }
}

@Composable
private fun CoachMarkBanner(
    coach: CoachMark,
    stepIndex: Int,
    totalSteps: Int,
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .border(1.dp, AppTheme.colors.borderSecondary.color, Radii.Card.shape)
            .padding(Dimension.D500),
    ) {
        // Step counter line — keeps the player oriented (1 / 12, etc.).
        Text(
            text = "Step ${stepIndex + 1} of $totalSteps",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        if (!coach.title.isNullOrBlank()) {
            VerticalSpacerD200()
            Text(
                text = coach.title,
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.text,
            )
        }
        VerticalSpacerD200()
        Text(
            text = coach.body,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        if (!coach.ctaLabel.isNullOrBlank()) {
            VerticalSpacerD400()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ButtonPrimary(
                    onClick = onAdvance,
                    size = ButtonSize.Small,
                ) {
                    Text(coach.ctaLabel)
                }
            }
        }
    }
}

@Composable
private fun TutorialCompletedScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "🎓",
                typography = AppTheme.typography.Display.D1400,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD800()
            Text(
                text = "You're ready",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD500()
            Text(
                text = "Raise the strong hands, call when the price is right, fold the rest. The bots are waiting in Practice.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD800()
            ButtonPrimary(
                onClick = onExit,
                size = ButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

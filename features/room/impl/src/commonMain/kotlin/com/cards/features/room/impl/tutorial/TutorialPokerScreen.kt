package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.dangerfield.cards.features.room.impl.PlayPokerAction
import com.dangerfield.cards.features.room.impl.PlayPokerScreen
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonTertiary
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.ui.tooling.preview.Preview

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
    onSkipBasics: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.completed) {
        TutorialCompletedScreen(onExit = onExit, modifier = modifier)
        return
    }

    // Narration-only intro steps have no fabricated table — render a
    // clean centered explainer instead of overlaying on PlayPokerScreen.
    val tableau = state.step.state
    if (tableau == null) {
        NarrationStep(
            step = state.step,
            stepIndex = state.stepIndex,
            totalSteps = state.totalSteps,
            onAdvance = onAdvance,
            onSkipBasics = onSkipBasics,
            onExit = onExit,
            modifier = modifier,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        PlayPokerScreen(
            state = tableau,
            onAction = { action ->
                if (action is PlayPokerAction.Submit) onIntent(action.intent)
                // All other actions (LeaveTable, RequestNextHand,
                // BlastEmoji, etc.) are no-ops for the tutorial. We don't
                // dispatch into a real engine — the script controls flow.
            },
            onBack = onExit,
        )

        val placement = state.step.coach.placement
        val alignment = when (placement) {
            CoachMarkPlacement.Top -> Alignment.TopCenter
            CoachMarkPlacement.Bottom -> Alignment.BottomCenter
        }
        val insetSides = when (placement) {
            CoachMarkPlacement.Top -> WindowInsetsSides.Top
            CoachMarkPlacement.Bottom -> WindowInsetsSides.Bottom
        }
        // Top placement clears the play screen's top bar (back / level
        // pill). Bottom placement just hugs the safe-area inset —
        // narration steps don't show the action bar (humanLegalActions
        // is null), so we don't need to reserve space for it.
        val topPadding = if (placement == CoachMarkPlacement.Top) 56.dp else 0.dp
        val bottomPadding = if (placement == CoachMarkPlacement.Bottom) 16.dp else 0.dp

        // Per-step drag offset — keyed on stepIndex so each new step
        // starts at the placement default. Keeping drag across steps
        // would defeat the point of per-step placement (we picked the
        // default specifically so opponents / hole cards stay visible).
        var dragOffset by remember(state.stepIndex) { mutableStateOf(Offset.Zero) }

        CoachMarkBanner(
            coach = state.step.coach,
            stepIndex = state.stepIndex,
            totalSteps = state.totalSteps,
            onAdvance = onAdvance,
            onDrag = { dragOffset += it },
            modifier = Modifier
                .align(alignment)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(insetSides))
                .padding(
                    top = topPadding,
                    bottom = bottomPadding,
                    start = 12.dp,
                    end = 12.dp,
                )
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) },
        )
    }
}

@Composable
private fun CoachMarkBanner(
    coach: CoachMark,
    stepIndex: Int,
    totalSteps: Int,
    onAdvance: () -> Unit,
    onDrag: (Offset) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .border(1.dp, AppTheme.colors.borderSecondary.color, Radii.Card.shape)
            // Listen on the whole banner so the user can grab anywhere
            // non-interactive (gaps, text, the step counter). The CTA
            // button still receives taps because tap doesn't cross the
            // touch-slop threshold that arms the drag detector.
            .pointerInput(stepIndex) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
            .padding(Dimension.D500),
    ) {
        // iOS-style grabber — pure visual affordance; the whole banner
        // is the actual drag target.
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AppTheme.colors.borderSecondary.color),
        )
        VerticalSpacerD200()
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

/**
 * Centered explainer card used for the foundational poker-rules intro
 * steps (the ones marked `isBasics = true`). No fabricated table —
 * just hero glyph, title, body, primary CTA, and a Tertiary "Skip
 * basics" button so experienced players can jump straight to Hand 1.
 */
@Composable
private fun NarrationStep(
    step: TutorialStep,
    stepIndex: Int,
    totalSteps: Int,
    onAdvance: () -> Unit,
    onSkipBasics: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(
        modifier = modifier,
        topBar = { TopBar(onNavigateBack = onExit) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!step.heroGlyph.isNullOrBlank()) {
                Text(
                    text = step.heroGlyph,
                    typography = AppTheme.typography.Display.D1400,
                    color = AppTheme.colors.text,
                )
                VerticalSpacerD800()
            }
            Text(
                text = "Step ${stepIndex + 1} of $totalSteps",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
            VerticalSpacerD200()
            if (!step.coach.title.isNullOrBlank()) {
                Text(
                    text = step.coach.title,
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.text,
                    textAlign = TextAlign.Center,
                )
                VerticalSpacerD500()
            }
            Text(
                text = step.coach.body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD800()
            ButtonPrimary(
                onClick = onAdvance,
                size = ButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(step.coach.ctaLabel ?: "Next")
            }
            // Skip-basics escape hatch — only shown on the intro
            // narration block. Once we're past the basics there's
            // nothing to skip; the button vanishes.
            if (step.isBasics) {
                VerticalSpacerD400()
                ButtonTertiary(
                    onClick = onSkipBasics,
                    size = ButtonSize.Small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Skip basics — I know how to play")
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

@Preview
@Composable
private fun TutorialPokerScreenPreview_Orient() {
    PreviewContent {
        val script = TutorialScript.steps
        TutorialPokerScreen(
            state = TutorialState(
                step = script.first(),
                stepIndex = 0,
                totalSteps = script.size,
                completed = false,
            ),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun TutorialPokerScreenPreview_ActionPrompt() {
    PreviewContent {
        val script = TutorialScript.steps
        val actionStepIndex = script.indexOfFirst { it.advanceOn != null }
            .coerceAtLeast(0)
        TutorialPokerScreen(
            state = TutorialState(
                step = script[actionStepIndex],
                stepIndex = actionStepIndex,
                totalSteps = script.size,
                completed = false,
            ),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun TutorialPokerScreenPreview_Completed() {
    PreviewContent {
        val script = TutorialScript.steps
        TutorialPokerScreen(
            state = TutorialState(
                step = script.last(),
                stepIndex = script.lastIndex,
                totalSteps = script.size,
                completed = true,
            ),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun TutorialPokerScreenPreview_Intro() {
    PreviewContent {
        val script = TutorialScript.steps
        val introIndex = script.indexOfFirst { it.isBasics }.coerceAtLeast(0)
        TutorialPokerScreen(
            state = TutorialState(
                step = script[introIndex],
                stepIndex = introIndex,
                totalSteps = script.size,
                completed = false,
            ),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun CoachMarkBannerPreview_Narration() {
    PreviewContent {
        Box(modifier = Modifier.padding(Dimension.D500)) {
            CoachMarkBanner(
                coach = CoachMark(
                    title = "Your cards",
                    body = "Two aces — the strongest starting hand in poker. You're a clear favorite to win this pot.",
                    ctaLabel = "Nice",
                ),
                stepIndex = 2,
                totalSteps = 12,
                onAdvance = {},
            )
        }
    }
}

@Preview
@Composable
private fun CoachMarkBannerPreview_ActionPrompt() {
    PreviewContent {
        Box(modifier = Modifier.padding(Dimension.D500)) {
            CoachMarkBanner(
                coach = CoachMark(
                    title = "Raise",
                    body = "When you have the best of it, raise to build the pot. Tap Raise to put more chips in.",
                    ctaLabel = null,
                ),
                stepIndex = 3,
                totalSteps = 12,
                onAdvance = {},
            )
        }
    }
}

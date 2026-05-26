package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.dangerfield.cards.features.room.impl.PlayPokerAction
import com.dangerfield.cards.features.room.impl.PlayPokerScreen
import com.dangerfield.cards.libraries.cards.AchievementId
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementUnlockReveal
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonTertiary
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
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
 *   [TableUiState.Active.humanLegalActions], the action bar already
 *   respects `canCall`/`canCheck`/`canRaise`.
 * - Tutorial advancement intercepts [PlayPokerAction.Submit]; the
 *   [TutorialScript] step's `advanceOn` predicate decides whether the
 *   submitted intent counts.
 * - Other [PlayPokerAction] variants (LeaveTable, BlastEmoji, etc.) are
 *   silently dropped, the tutorial doesn't fire telemetry or progression.
 */
@Composable
internal fun TutorialPokerScreen(
    state: TutorialState,
    onIntent: (com.dangerfield.cards.libraries.gameplay.PlayerIntent) -> Unit,
    onAdvance: () -> Unit,
    onSkipBasics: () -> Unit,
    onRestartBasics: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.completed) {
        TutorialCompletedScreen(onExit = onExit, modifier = modifier)
        return
    }

    // Back-press intercepts open the tutorial leave dialog instead of
    // exiting outright. Gives the user a chance to bail back to the
    // basics primer if they got into the tableau and realized they
    // needed the rules first. Lifted to this level so both step types
    // share the same affordance.
    var leaveDialogOpen by remember { mutableStateOf(false) }
    val openLeaveDialog: () -> Unit = { leaveDialogOpen = true }

    Box(modifier = modifier.fillMaxSize()) {
        val tableau = state.step.state
        if (tableau == null) {
            NarrationStep(
                step = state.step,
                onAdvance = onAdvance,
                onSkipBasics = onSkipBasics,
                onExit = openLeaveDialog,
            )
        } else {
            TableauStep(
                tableau = tableau,
                step = state.step,
                stepIndex = state.stepIndex,
                onIntent = onIntent,
                onAdvance = onAdvance,
                onExit = openLeaveDialog,
            )
        }
        StepCounterPill(
            section = state.section,
            sectionStep = state.sectionStepIndex + 1,
            sectionTotal = state.sectionTotalSteps,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = 14.dp),
        )
    }

    if (leaveDialogOpen) {
        TutorialLeaveDialog(
            // "Back to basics" only makes sense when we're NOT already
            // there. Hides the button on basics steps so the dialog
            // doesn't offer a self-restart loop.
            showBackToBasics = state.section != TutorialSection.Basics,
            onBackToBasics = {
                onRestartBasics()
                leaveDialogOpen = false
            },
            onExit = {
                leaveDialogOpen = false
                onExit()
            },
            onDismiss = { leaveDialogOpen = false },
        )
    }
}

/**
 * Tableau step rendering. Owns the animated coach-mark position so
 * transitions between Top/Middle/Bottom placements slide instead of
 * snap. Drag offset is per-step (`remember(stepIndex)`) so the user
 * starts each step at the placement default we picked for it.
 */
@Composable
private fun TableauStep(
    tableau: com.dangerfield.cards.features.room.impl.PlayPokerState,
    step: TutorialStep,
    stepIndex: Int,
    onIntent: (com.dangerfield.cards.libraries.gameplay.PlayerIntent) -> Unit,
    onAdvance: () -> Unit,
    onExit: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        PlayPokerScreen(
            state = tableau,
            onAction = { action ->
                if (action is PlayPokerAction.Submit) onIntent(action.intent)
                // Other actions (LeaveTable, RequestNextHand, BlastEmoji)
                // are no-ops. The script controls flow, not a real
                // engine.
            },
            onBack = onExit,
            // Hide the centered Level pill so our step-counter pill
            // owns the top-bar middle slot without colliding.
            showXpPill = false,
            // No real hand or XP at stake in the tutorial, so back-out
            // skips the "you'll lose this hand" confirm dialog.
            confirmLeave = false,
        )

        val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
        val layoutDir = LocalLayoutDirection.current
        val topSafe = safeInsets.calculateTopPadding()
        val bottomSafe = safeInsets.calculateBottomPadding()

        // Measure the banner so Middle/Bottom placements can position
        // it precisely. First frame: bannerHeight = 0 → banner lands at
        // its target without animation. Second frame: bannerHeight is
        // known, animation begins. Imperceptible in practice.
        var bannerHeightDp by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        val placement = step.coach.placement
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val maxH = maxHeight
            // 56dp clears the play screen's top bar (back chevron +
            // level pill row). The pill counter is 14 + ~28dp tall, so
            // 60dp total reserves space for both.
            val topAnchor = topSafe + 60.dp
            val middleAnchor = (maxH - bannerHeightDp) / 2
            val bottomAnchor = maxH - bannerHeightDp - bottomSafe - 16.dp
            val targetY = when (placement) {
                CoachMarkPlacement.Top -> topAnchor
                CoachMarkPlacement.Middle -> middleAnchor.coerceAtLeast(topAnchor)
                CoachMarkPlacement.Bottom -> bottomAnchor.coerceAtLeast(topAnchor)
            }
            val animatedY by animateDpAsState(
                targetValue = targetY,
                animationSpec = tween(durationMillis = 320),
                label = "coachMarkY",
            )

            // Per-step drag offset, keyed on stepIndex so each new step
            // starts at its placement-default Y. Keeping drag across
            // steps would defeat per-step placement (we picked each
            // default specifically to keep important UI visible).
            var dragOffset by remember(stepIndex) { mutableStateOf(Offset.Zero) }

            CoachMarkBanner(
                coach = step.coach,
                stepIndex = stepIndex,
                onAdvance = onAdvance,
                onDrag = { dragOffset += it },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = 12.dp + safeInsets.calculateStartPadding(layoutDir),
                        end = 12.dp + safeInsets.calculateEndPadding(layoutDir),
                    )
                    .offset(y = animatedY)
                    .onSizeChanged { size ->
                        bannerHeightDp = with(density) { size.height.toDp() }
                    }
                    .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) },
            )
        }
    }
}

@Composable
private fun CoachMarkBanner(
    coach: CoachMark,
    stepIndex: Int,
    onAdvance: () -> Unit,
    onDrag: (Offset) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape)
            // Listen on the whole banner so the user can grab anywhere
            // non-interactive (gaps, text, bullets). The CTA button
            // still receives taps because tap doesn't cross the touch-
            // slop threshold that arms the drag detector.
            .pointerInput(stepIndex) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            }
            .padding(Dimension.D500),
    ) {
        // iOS-style grabber, pure visual affordance; the whole banner
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
        if (!coach.title.isNullOrBlank()) {
            Text(
                text = coach.title,
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.text,
            )
            VerticalSpacerD200()
        }
        Text(
            text = coach.body,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        if (coach.bullets.isNotEmpty()) {
            VerticalSpacerD200()
            BulletList(bullets = coach.bullets)
        }
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
 * Pinned-top-center step pill rendered above both narration and
 * tableau steps. Lives in the topbar's empty middle gap rather than
 * being structurally inside any topbar, keeps the pill consistent
 * across step types and out of the back-button's way.
 */
@Composable
private fun StepCounterPill(
    section: TutorialSection,
    sectionStep: Int,
    sectionTotal: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .border(1.dp, AppTheme.colors.border.color, RoundedCornerShape(999.dp))
            .padding(horizontal = Dimension.D500, vertical = Dimension.D200),
    ) {
        Text(
            text = "Step $sectionStep of $sectionTotal · ${section.displayName}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

/**
 * Left-aligned bullet list. Used both in the floating coach mark and
 * in the centered narration card; centered bullets read awkwardly so
 * the list always anchors Start regardless of caller's text alignment.
 */
@Composable
private fun BulletList(
    bullets: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        bullets.forEachIndexed { index, bullet ->
            if (index > 0) VerticalSpacerD200()
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = "•",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.padding(end = Dimension.D300),
                )
                Text(
                    text = bullet,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.textSecondary,
                )
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
    onAdvance: () -> Unit,
    onSkipBasics: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(
        modifier = modifier,
        topBar = { TopBar(onNavigateBack = onExit) },
    ) { padding ->
        // Hero floats centered in the upper half; headline + body +
        // CTA anchor to the bottom. The weighted Spacers do the work
        // without measuring; weight(1f) above and below the headline
        // block pushes content into a top-third / bottom-third layout
        // that breathes on tall and short screens alike.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The StepCounterPill renders separately as a top-center
            // overlay, so we don't need to reserve space here for it.
            // Just leave headroom below the topbar.
            VerticalSpacerD800()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                NarrationHeroBlock(hero = step.hero)
            }
            if (!step.coach.title.isNullOrBlank()) {
                Text(
                    text = step.coach.title,
                    typography = AppTheme.typography.Display.D900.Italic,
                    color = ColorResource.Amber500,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacerD400()
            }
            Text(
                text = step.coach.body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD800()
            ButtonPrimary(
                onClick = onAdvance,
                size = ButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(step.coach.ctaLabel ?: "Next")
            }
            // Skip basics moves from button-styled to text-link styled:
            // visually quieter, doesn't compete with the primary CTA.
            // Still tappable on every basics step until the user moves
            // past the section.
            if (step.isBasics) {
                VerticalSpacerD400()
                ButtonTertiary(
                    onClick = onSkipBasics,
                    size = ButtonSize.Small,
                ) {
                    Text(
                        text = "Skip basics, I know how to play",
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.textSecondary,
                    )
                }
            }
            VerticalSpacerD600()
        }
    }
}

/**
 * Top-area hero for narration steps. Each [NarrationHero] variant
 * renders an all-caps category label above its bespoke visual. Plain
 * Box returned for null hero so callers can size unconditionally.
 */
@Composable
private fun NarrationHeroBlock(hero: NarrationHero?) {
    if (hero == null) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Category chip pill. Tiny all-caps label hovers above the
        // visual so the user gets a glance-readable cue ("THE POT")
        // before scanning the bigger illustration.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(AppTheme.colors.surfaceSecondary.color)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                text = hero.category.uppercase(),
                typography = AppTheme.typography.Label.L400,
                color = ColorResource.Amber500,
            )
        }
        VerticalSpacerD800()
        when (hero) {
            NarrationHero.Pot -> PotHero()
            NarrationHero.HandRanks -> HandRanksHero()
            NarrationHero.Actions -> ActionsHero()
        }
    }
}

/**
 * "The pot" hero: three colored discs stacked slightly offset to
 * suggest a chip pile. Discs are circles rather than realistic chip
 * art because (a) we don't have chip illustrations in the DS, and (b)
 * abstract shapes read cleaner at this size than detail-rich icons.
 */
@Composable
private fun PotHero() {
    Box(
        modifier = Modifier.size(width = 140.dp, height = 110.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Disc(
            color = androidx.compose.ui.graphics.Color(0xFF3E7BFA),
            modifier = Modifier
                .offset(x = (-28).dp, y = (-8).dp)
                .size(56.dp),
        )
        Disc(
            color = androidx.compose.ui.graphics.Color(0xFFE5B946),
            modifier = Modifier
                .offset(x = 26.dp, y = (-4).dp)
                .size(56.dp),
        )
        Disc(
            color = androidx.compose.ui.graphics.Color(0xFFE05656),
            modifier = Modifier.size(72.dp),
        )
    }
}

@Composable
private fun Disc(
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(color)
            .border(
                width = 2.dp,
                color = color.copy(alpha = 0.6f),
                shape = androidx.compose.foundation.shape.CircleShape,
            ),
    )
}

/**
 * "Hands" hero: three example rank cards stacked, weakest at the top
 * and strongest highlighted at the bottom. Mirrors the screenshot's
 * "high card -> pair -> three of a kind" progression. The highlighted
 * row uses the amber accent so the eye lands on the strongest hand
 * first, which is what the headline below ("better hands beat worse
 * ones") wants the user to internalize.
 */
@Composable
private fun HandRanksHero() {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RankRow(cards = "K♠  9♥", title = "High card", subtitle = "Just your highest card", highlighted = false)
        RankRow(cards = "Q♦  Q♣", title = "Pair", subtitle = "Two of the same rank", highlighted = false)
        RankRow(cards = "A♥  A♠  A♦", title = "Three of a kind", subtitle = "Three of the same rank", highlighted = true)
    }
}

@Composable
private fun RankRow(
    cards: String,
    title: String,
    subtitle: String,
    highlighted: Boolean,
) {
    val border = if (highlighted) ColorResource.Amber500.color else AppTheme.colors.borderSecondary.color
    val titleColor = if (highlighted) ColorResource.Amber500 else AppTheme.colors.text
    Row(
        modifier = Modifier
            .clip(Radii.R400.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .border(1.dp, border, Radii.R400.shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = cards,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
            modifier = Modifier.width(78.dp),
        )
        Column {
            Text(
                text = title,
                typography = AppTheme.typography.Body.B500.SemiBold,
                color = titleColor,
            )
            Text(
                text = subtitle,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * "Actions" hero: Fold / Call / Raise rendered as a small legend with
 * the action name on the left and a one-line description on the
 * right. Matches the at-the-table action bar's vocabulary so the
 * mapping from this card to the live UI is immediate.
 */
@Composable
private fun ActionsHero() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionLegendRow(label = "Fold", description = "Throw away your cards. You're out of this hand.", dimmed = true)
        ActionLegendRow(label = "Call", description = "Match the current bet. Stay in the hand.", dimmed = false)
        ActionLegendRow(label = "Raise", description = "Bet more than the current bet. Pressure your opponents.", dimmed = false)
    }
}

@Composable
private fun ActionLegendRow(
    label: String,
    description: String,
    dimmed: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(74.dp)
                .clip(RoundedCornerShape(999.dp))
                .then(
                    if (dimmed) Modifier.border(
                        width = 1.dp,
                        color = AppTheme.colors.borderSecondary.color,
                        shape = RoundedCornerShape(999.dp),
                    ) else Modifier,
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                typography = AppTheme.typography.Body.B500.SemiBold,
                color = if (dimmed) AppTheme.colors.textSecondary else AppTheme.colors.text,
            )
        }
        Row(modifier = Modifier.width(12.dp)) {}
        Text(
            text = description,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Three-option leave dialog opened when the user taps back on any
 * tutorial step. Replaces PlayPokerScreen's bots-table confirm dialog,
 * which talks about losing XP and chips (irrelevant here).
 *
 * "Back to basics" is the differentiator: a player who skipped the
 * basics and got disoriented in the tableau can land back at the
 * primer in one tap instead of restarting the whole tutorial.
 */
@Composable
private fun TutorialLeaveDialog(
    showBackToBasics: Boolean,
    onBackToBasics: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    com.dangerfield.cards.libraries.ui.components.dialog.Dialog(
        onDismissRequest = onDismiss,
        topAccessory = com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji(
            emoji = "🎓",
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Leave the tutorial?",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (showBackToBasics) {
                    "Bail out, or jump back to the basics primer if you need a refresher first."
                } else {
                    "Exit the tutorial? You can restart it anytime from Settings."
                },
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            if (showBackToBasics) {
                ButtonPrimary(
                    onClick = onBackToBasics,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back to basics") }
            }
            ButtonTertiary(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Exit tutorial") }
            ButtonTertiary(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun TutorialCompletedScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tutorialAchievement = AllAchievementsById[AchievementId.TUTORIAL_COMPLETE]
    Screen(modifier = modifier) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Full reveal sequence: mystery card shakes, bursts open
            // with a particle spray, then the real medallion slams in.
            // Haptics fire at each beat (shake peaks, burst ignition,
            // medallion impact). Fallback to a static spacer if the
            // achievement is missing from the registry (drift safety).
            if (tutorialAchievement != null) {
                Box(modifier = Modifier.size(200.dp)) {
                    AchievementUnlockReveal(achievement = tutorialAchievement)
                }
            }
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

/** Builds a [TutorialState] from a script index, mirroring the VM's
 *  `stateForIndex` logic so previews show the same section-counter
 *  numbers the app would render. Test/preview only. */
private fun previewState(index: Int, completed: Boolean = false): TutorialState {
    val script = TutorialScript.steps
    val step = script[index]
    val inSection = script.filter { it.section == step.section }
    return TutorialState(
        step = step,
        stepIndex = index,
        totalSteps = script.size,
        sectionStepIndex = inSection.indexOfFirst { it === step }.coerceAtLeast(0),
        sectionTotalSteps = inSection.size,
        completed = completed,
    )
}

@Preview
@Composable
private fun TutorialPokerScreenPreview_Orient() {
    PreviewContent {
        TutorialPokerScreen(
            state = previewState(index = 0),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onRestartBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun TutorialPokerScreenPreview_ActionPrompt() {
    PreviewContent {
        val actionStepIndex = TutorialScript.steps
            .indexOfFirst { it.advanceOn != null }
            .coerceAtLeast(0)
        TutorialPokerScreen(
            state = previewState(index = actionStepIndex),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onRestartBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun TutorialPokerScreenPreview_Completed() {
    PreviewContent {
        TutorialPokerScreen(
            state = previewState(index = TutorialScript.steps.lastIndex, completed = true),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onRestartBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun TutorialPokerScreenPreview_Intro() {
    PreviewContent {
        val introIndex = TutorialScript.steps.indexOfFirst { it.isBasics }.coerceAtLeast(0)
        TutorialPokerScreen(
            state = previewState(index = introIndex),
            onIntent = {},
            onAdvance = {},
            onSkipBasics = {},
            onRestartBasics = {},
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
                    body = "Two aces, the strongest starting hand in poker. You're a clear favorite to win this pot.",
                    ctaLabel = "Nice",
                ),
                stepIndex = 2,
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
                onAdvance = {},
            )
        }
    }
}

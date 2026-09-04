package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.features.room.impl.ui.label

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.tutorial_actions_call_description
import cards.libraries.resources.generated.resources.tutorial_actions_call_label
import cards.libraries.resources.generated.resources.tutorial_actions_fold_description
import cards.libraries.resources.generated.resources.tutorial_actions_fold_label
import cards.libraries.resources.generated.resources.tutorial_actions_raise_description
import cards.libraries.resources.generated.resources.tutorial_actions_raise_label
import cards.libraries.resources.generated.resources.tutorial_eyebrow_goal
import cards.libraries.resources.generated.resources.tutorial_eyebrow_hand
import cards.libraries.resources.generated.resources.tutorial_eyebrow_move
import cards.libraries.resources.generated.resources.tutorial_handranks_row1_subtitle
import cards.libraries.resources.generated.resources.tutorial_handranks_row1_title
import cards.libraries.resources.generated.resources.tutorial_handranks_row2_subtitle
import cards.libraries.resources.generated.resources.tutorial_handranks_row2_title
import cards.libraries.resources.generated.resources.tutorial_handranks_row3_subtitle
import cards.libraries.resources.generated.resources.tutorial_handranks_row3_title
import cards.libraries.resources.generated.resources.tutorial_narration_default_cta
import cards.libraries.resources.generated.resources.tutorial_skip_basics_button
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Card as DsCard
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension.D1000
import com.dangerfield.cards.system.Dimension.D500
import com.dangerfield.cards.system.DimensionResource
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.then
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Centered explainer card used for the foundational poker-rules
 * intro steps (those in [TutorialSection.Basics]). No fabricated
 * table behind, just a hero illustration up top, serif italic
 * amber headline + body anchored to the bottom, primary CTA, and a
 * quiet text-link "Skip basics" for experienced players.
 *
 * Lives in its own file because the bespoke hero illustrations
 * (chip stack, hand-rank cards, action legend) plus their per-step
 * previews are substantial enough that bundling them with the
 * tableau / completion / leave-dialog code made TutorialPokerScreen
 * unwieldy to navigate.
 */
@Composable
internal fun NarrationStep(
    step: TutorialStep,
    section: TutorialSection,
    sectionStepIndex: Int,
    sectionTotalSteps: Int,
    onAdvance: () -> Unit,
    onSkipBasics: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(
        modifier = modifier,
        topBar = {
            TopBar(
                onNavigateBack = onBack,
                // Centered in the bar (matching the tableau steps' centered
                // step pill) so progress reads consistently across the tutorial.
                centerContent = {
                    StepCounterPill(
                        section = section,
                        sectionStep = sectionStepIndex + 1,
                        sectionTotal = sectionTotalSteps,
                    )
                },
            )
        },
    ) { padding ->
        // Slide horizontally on advance and back-step. Direction is
        // inferred from the index delta: forward (target > initial)
        // slides incoming in from the right and pushes outgoing out
        // to the left; back-step inverts both. Section-step index is
        // the right key here: section-bounded so direction stays
        // intuitive across the basics block, and stable across
        // recompositions inside the section.
        AnimatedContent(
            targetState = sectionStepIndex to step,
            transitionSpec = {
                val targetIdx = targetState.first
                val initialIdx = initialState.first
                val forward = targetIdx >= initialIdx
                if (forward) {
                    (slideInHorizontally(animationSpec = tween(280)) { it } +
                        fadeIn(animationSpec = tween(200))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(280)) { -it } +
                            fadeOut(animationSpec = tween(200)))
                } else {
                    (slideInHorizontally(animationSpec = tween(280)) { -it } +
                        fadeIn(animationSpec = tween(200))) togetherWith
                        (slideOutHorizontally(animationSpec = tween(280)) { it } +
                            fadeOut(animationSpec = tween(200)))
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "narration-step",
        ) { (_, animatedStep) ->
            // Hero floats centered in the upper half; headline + body +
            // CTA anchor to the bottom. The weighted Box does the work
            // without measuring: weight(1f) above the headline block
            // pushes content into a top-third / bottom-third layout
            // that breathes on tall and short screens alike.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    NarrationHeroBlock(hero = animatedStep.hero)
                }
                // Eyebrow/kicker categorising the gold headline — "Your goal"
                // / "Your hand" / "Your move" — so each basics card states
                // what the headline is teaching before the headline itself.
                val eyebrowRes = eyebrowFor(animatedStep.hero)
                if (eyebrowRes != null) {
                    Text(
                        text = stringResource(eyebrowRes).uppercase(),
                        typography = AppTheme.typography.Label.L600,
                        color = AppTheme.colors.contentSecondary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VerticalSpacerD200()
                }
                val titleRes = animatedStep.coach.title
                if (titleRes != null) {
                    Text(
                        text = stringResource(titleRes),
                        typography = AppTheme.typography.Display.D1100.Italic,
                        color = AppTheme.colors.accentPrimary,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VerticalSpacerD400()
                }
                Text(
                    text = stringResource(animatedStep.coach.body),
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacerD800()
                ButtonPrimary(
                    onClick = onAdvance,
                    size = ButtonSize.Medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val ctaRes = animatedStep.coach.ctaLabel
                    Text(stringResource(ctaRes ?: Res.string.tutorial_narration_default_cta))
                }
                // Skip basics is a quiet text-link, not a full-width
                // button: visually doesn't compete with the primary CTA.
                // Still tappable on every basics step until the user
                // moves past the section.
                if (animatedStep.isBasics) {
                    VerticalSpacerD400()
                    Button(
                        onClick = onSkipBasics,
                        type = ButtonType.Primary,
                        accent = ButtonAccent.Secondary,
                        style = ButtonStyle.Text,
                        size = ButtonSize.Small,
                        content = { Text(stringResource(Res.string.tutorial_skip_basics_button)) }
                    )
                }
                VerticalSpacerD600()
            }
        }
    }
}

/**
 * Top-area hero for narration steps. Each [NarrationHero] variant
 * renders a bespoke visual; the user-facing step name lives in the
 * top-center [StepCounterPill] so a duplicate in-hero label would
 * just be visual noise. No-op when [hero] is null so callers can
 * size unconditionally without branching on presence.
 */
/**
 * The eyebrow label that categorises each basics headline: the goal, the
 * hand-ranking idea, or the available moves. Null for narration steps without
 * a hero (no headline to caption).
 */
private fun eyebrowFor(hero: NarrationHero?): StringResource? = when (hero) {
    NarrationHero.Pot -> Res.string.tutorial_eyebrow_goal
    NarrationHero.HandRanks -> Res.string.tutorial_eyebrow_hand
    NarrationHero.Actions -> Res.string.tutorial_eyebrow_move
    null -> null
}

@Composable
private fun NarrationHeroBlock(hero: NarrationHero?) {
    if (hero == null) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (hero) {
            NarrationHero.Pot -> PotHero()
            NarrationHero.HandRanks -> HandRanksHero()
            NarrationHero.Actions -> ActionsHero()
        }
    }
}

// ---------------------------------------------------------------------
// "The pot" hero
// ---------------------------------------------------------------------

/**
 * Gold coin with an animated number counting up underneath. Reads as
 * "value accumulating," matching the headline's "win the pot" framing.
 * The ramp uses EaseOutCubic so the count climbs fast and lands soft,
 * the way a real chip tally feels at a table.
 *
 * Target is hardcoded to 1,250: round enough to feel like a meaningful
 * pot, small enough that the count-up reads at a glance.
 */
@Composable
private fun PotHero() {
    val target = 1_250
    val animated = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Brief hold at 0 so the user registers the starting state
        // before the ramp begins. Without this the count feels like
        // it's already in motion when the screen arrives.
        delay(160)
        animated.animateTo(
            targetValue = target.toFloat(),
            animationSpec = tween(durationMillis = 1400, easing = EaseOutCubic),
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Coin(modifier = Modifier.size(96.dp))
        VerticalSpacerD600()
        Text(
            text = formatChipCount(animated.value.toInt()),
            typography = AppTheme.typography.Display.D1100,
            color = AppTheme.colors.content,
        )
    }
}

/**
 * A single gold-coin disc. Radial gradient gives a soft "lit from
 * above" highlight, the darker rim sells the metal edge. Center
 * glyph is "$" for universal value readability; chip art would
 * carry more meaning but isn't in the DS yet.
 */
@Composable
private fun Coin(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        AppTheme.colors.poker.coinGradientStart.color,
                        AppTheme.colors.poker.coinGradientEnd.color,
                    ),
                ),
            )
            .border(3.dp, AppTheme.colors.poker.coinOutline.color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$",
            typography = AppTheme.typography.Display.D1100,
            color = AppTheme.colors.poker.coinGlyph,
        )
    }
}

/** Comma-group an integer for the counter display. Stays purely local
 *  to this file so we don't reach for a heavier locale-aware formatter
 *  on a value that is by construction non-negative and small. */
private fun formatChipCount(amount: Int): String {
    val safe = amount.coerceAtLeast(0)
    return safe.toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()
}

// ---------------------------------------------------------------------
// "Hands" hero
// ---------------------------------------------------------------------

/**
 * Three example rank rows stacked weakest -> strongest. The bottom
 * row is amber-accented so the eye lands on the strongest hand
 * first, mirroring the headline's "better hands beat worse ones"
 * framing.
 */
@Composable
private fun HandRanksHero() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RankRow(
            cards = listOf(
                Card(Rank.King, Suit.Spades),
                Card(Rank.Nine, Suit.Hearts),
            ),
            title = Res.string.tutorial_handranks_row1_title,
            subtitle = Res.string.tutorial_handranks_row1_subtitle,
            highlighted = false,
        )
        RankRow(
            cards = listOf(
                Card(Rank.Queen, Suit.Diamonds),
                Card(Rank.Queen, Suit.Clubs),
            ),
            title = Res.string.tutorial_handranks_row2_title,
            subtitle = Res.string.tutorial_handranks_row2_subtitle,
            highlighted = false,
        )
        RankRow(
            cards = listOf(
                Card(Rank.Ace, Suit.Hearts),
                Card(Rank.Ace, Suit.Spades),
                Card(Rank.Ace, Suit.Diamonds),
            ),
            title = Res.string.tutorial_handranks_row3_title,
            subtitle = Res.string.tutorial_handranks_row3_subtitle,
            highlighted = true,
        )
    }
}

@Composable
private fun RankRow(
    cards: List<Card>,
    title: StringResource,
    subtitle: StringResource,
    highlighted: Boolean,
) {
    // Highlighted row gets an amber border layered over the Card.
    // Card itself doesn't expose a border slot, so we apply the
    // amber outline via a Modifier wrapping the Card. Non-highlighted
    // rows fall back to the DS border token so they pick up the
    // theme's stock outline without us hand-coding a color.
    val outline = if (highlighted) AppTheme.colors.accentPrimary.color else AppTheme.colors.border.color
    val titleColor = if (highlighted) AppTheme.colors.accentPrimary else AppTheme.colors.content
    DsCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, outline, Radii.Card.shape),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiniCardStack(
                cards = cards,
                modifier = Modifier.width(MiniCardStackSlotWidth),
            )
            Column {
                Text(
                    text = stringResource(title),
                    typography = AppTheme.typography.Body.B500.SemiBold,
                    color = titleColor,
                )
                Text(
                    text = stringResource(subtitle),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                )
            }
        }
    }
}

/**
 * Reserved width for the rank-row card stack. Sized to fit up to
 * three [PlayingCardSize.Mini] cards at the [MiniCardStackOverlap]
 * offset without clipping or wrapping. Pulled out so the Column
 * beside it can use the remaining space predictably across rows
 * with different card counts (high-card has 2, three-of-a-kind has 3).
 */
private val MiniCardStackSlotWidth = 76.dp

/** Horizontal offset between successive mini cards in a stack. Small
 *  enough that each card's rank corner peeks through behind the next
 *  one, large enough that all three ranks are individually legible. */
private val MiniCardStackOverlap = 18.dp

/**
 * Horizontal stack of small [PlayingCard]s, each offset slightly from
 * the previous so they read as a "hand" rather than a deck. Uses the
 * DS [PlayingCardSize.Mini] preset so the cards visually match the
 * cheat-sheet and seat-indicator card renderings elsewhere in the app.
 */
@Composable
private fun MiniCardStack(
    cards: List<Card>,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        cards.forEachIndexed { index, card ->
            PlayingCard(
                card = card,
                size = PlayingCardSize.Mini,
                modifier = Modifier.offset(x = MiniCardStackOverlap * index),
            )
        }
    }
}

// ---------------------------------------------------------------------
// "Actions" hero
// ---------------------------------------------------------------------

/**
 * Fold / Call / Raise rendered as a small legend with the action
 * name in a pill on the left and a one-line description on the
 * right. Matches the at-the-table action bar's vocabulary so the
 * mapping from this card to the live UI is immediate.
 */
@Composable
private fun ActionsHero(
) {
    Column(
        modifier = Modifier.fillMaxWidth(0.85f),
        verticalArrangement = Arrangement.spacedBy(D1000)
    ) {
        ActionLegendRow(
            color = AppTheme.colors.danger,
            label = Res.string.tutorial_actions_fold_label,
            description = Res.string.tutorial_actions_fold_description,
        )
        ActionLegendRow(
            color = AppTheme.colors.surface,
            label = Res.string.tutorial_actions_call_label,
            description = Res.string.tutorial_actions_call_description,
        )
        ActionLegendRow(
            color = AppTheme.colors.surfaceRaised,
            label = Res.string.tutorial_actions_raise_label,
            description = Res.string.tutorial_actions_raise_description,
        )
    }
}

@Composable
private fun ActionLegendRow(
    label: StringResource,
    description: StringResource,
    color: ColorResource
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(74.dp)
                .clip(Radii.Round.shape)
                .background(color.color)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(label),
                typography = AppTheme.typography.Body.B600.SemiBold,
                color = AppTheme.colors.content,
            )
        }
        Row(modifier = Modifier.width(12.dp)) {}
        Text(
            text = stringResource(description),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------

/**
 * Builds a synthetic [TutorialStep] for a basics card preview. Picks
 * the step out of the live [TutorialScript] by section + hero so the
 * preview always reflects the shipped copy.
 */
private fun previewBasicsStep(hero: NarrationHero): TutorialStep {
    return TutorialScript.steps.first { it.isBasics && it.hero == hero }
}

@Preview
@Composable
private fun NarrationStepPreview_Pot() {
    PreviewContent {
        NarrationStep(
            step = previewBasicsStep(NarrationHero.Pot),
            section = TutorialSection.Basics,
            sectionStepIndex = 0,
            sectionTotalSteps = 3,
            onAdvance = {},
            onSkipBasics = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun NarrationStepPreview_HandRanks() {
    PreviewContent {
        NarrationStep(
            step = previewBasicsStep(NarrationHero.HandRanks),
            section = TutorialSection.Basics,
            sectionStepIndex = 1,
            sectionTotalSteps = 3,
            onAdvance = {},
            onSkipBasics = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun NarrationStepPreview_Actions() {
    PreviewContent {
        NarrationStep(
            step = previewBasicsStep(NarrationHero.Actions),
            section = TutorialSection.Basics,
            sectionStepIndex = 2,
            sectionTotalSteps = 3,
            onAdvance = {},
            onSkipBasics = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PotHeroPreview() {
    PreviewContent {
        Box(modifier = Modifier.padding(24.dp)) { PotHero() }
    }
}

@Preview
@Composable
private fun HandRanksHeroPreview() {
    PreviewContent {
        Box(modifier = Modifier.padding(24.dp)) { HandRanksHero() }
    }
}

@Preview
@Composable
private fun ActionsHeroPreview() {
    PreviewContent {
        Box(modifier = Modifier.padding(24.dp)) { ActionsHero() }
    }
}

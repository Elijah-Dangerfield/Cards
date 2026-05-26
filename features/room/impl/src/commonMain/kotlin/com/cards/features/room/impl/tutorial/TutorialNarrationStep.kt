package com.dangerfield.cards.features.room.impl.tutorial

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonTertiary
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD400
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
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
        // CTA anchor to the bottom. The weighted Box does the work
        // without measuring: weight(1f) above the headline block
        // pushes content into a top-third / bottom-third layout that
        // breathes on tall and short screens alike.
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
            // Skip basics is a quiet text-link, not a full-width
            // button: visually doesn't compete with the primary CTA.
            // Still tappable on every basics step until the user
            // moves past the section.
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
 * renders an all-caps category pill above its bespoke visual. No-op
 * when [hero] is null so callers can size unconditionally without
 * branching on presence.
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

// ---------------------------------------------------------------------
// "The pot" hero
// ---------------------------------------------------------------------

/**
 * Three colored discs stacked slightly offset to suggest a chip pile.
 * Abstract circles rather than realistic chip art because (a) we
 * don't have chip illustrations in the DS, and (b) abstract shapes
 * read cleaner at this size than detail-rich icons.
 */
@Composable
private fun PotHero() {
    Box(
        modifier = Modifier.size(width = 140.dp, height = 110.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Disc(
            color = Color(0xFF3E7BFA),
            modifier = Modifier
                .offset(x = (-28).dp, y = (-8).dp)
                .size(56.dp),
        )
        Disc(
            color = Color(0xFFE5B946),
            modifier = Modifier
                .offset(x = 26.dp, y = (-4).dp)
                .size(56.dp),
        )
        Disc(
            color = Color(0xFFE05656),
            modifier = Modifier.size(72.dp),
        )
    }
}

@Composable
private fun Disc(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(color)
            .border(
                width = 2.dp,
                color = color.copy(alpha = 0.6f),
                shape = CircleShape,
            ),
    )
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
            cards = "K♠  9♥",
            title = "High card",
            subtitle = "Just your highest card",
            highlighted = false,
        )
        RankRow(
            cards = "Q♦  Q♣",
            title = "Pair",
            subtitle = "Two of the same rank",
            highlighted = false,
        )
        RankRow(
            cards = "A♥  A♠  A♦",
            title = "Three of a kind",
            subtitle = "Three of the same rank",
            highlighted = true,
        )
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
private fun ActionsHero() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionLegendRow(
            label = "Fold",
            description = "Throw away your cards. You're out of this hand.",
            dimmed = true,
        )
        ActionLegendRow(
            label = "Call",
            description = "Match the current bet. Stay in the hand.",
            dimmed = false,
        )
        ActionLegendRow(
            label = "Raise",
            description = "Bet more than the current bet. Pressure your opponents.",
            dimmed = false,
        )
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
                    if (dimmed) {
                        Modifier.border(
                            width = 1.dp,
                            color = AppTheme.colors.borderSecondary.color,
                            shape = RoundedCornerShape(999.dp),
                        )
                    } else {
                        Modifier
                    },
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
            onAdvance = {},
            onSkipBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun NarrationStepPreview_HandRanks() {
    PreviewContent {
        NarrationStep(
            step = previewBasicsStep(NarrationHero.HandRanks),
            onAdvance = {},
            onSkipBasics = {},
            onExit = {},
        )
    }
}

@Preview
@Composable
private fun NarrationStepPreview_Actions() {
    PreviewContent {
        NarrationStep(
            step = previewBasicsStep(NarrationHero.Actions),
            onAdvance = {},
            onSkipBasics = {},
            onExit = {},
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

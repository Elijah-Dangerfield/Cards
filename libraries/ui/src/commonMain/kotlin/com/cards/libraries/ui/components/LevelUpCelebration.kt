package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_level_up_continue
import cards.libraries.resources.generated.resources.ui_level_up_level
import cards.libraries.resources.generated.resources.ui_level_up_message
import cards.libraries.resources.generated.resources.ui_level_up_rewards_header
import cards.libraries.resources.generated.resources.ui_level_up_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Full-screen, teal-identity celebration shown on Home when the user's
 * derived level crosses the [com.dangerfield.cards.libraries.cards.AppData]
 * `lastCelebratedLevel` watermark. A spinning [RotatingDial] burst re-skinned
 * in the level/XP teal frames the new level number, with a warm line and a
 * single Continue affordance.
 *
 * Deliberately a self-contained takeover (its own opaque background, centered
 * content) rather than a [com.dangerfield.cards.libraries.ui.components.dialog.Dialog]:
 * the moment wants the whole screen. Hosted by a routed full-screen destination
 * (no bottom bar) — never at the poker table — per `docs/decisions.md`
 * 2026-06-06. The entrance animates (scale + fade in) and a heavy haptic fires
 * as the dial lands so the level-up registers in the hand as well as the eye.
 *
 * @param level the level just reached (the net level on a multi-level jump).
 * @param onContinue fires only when the user taps Continue — there is no
 *   tap-to-dismiss; the host advances the watermark in response.
 * @param rewards the prizes granted for crossing into this level (already
 *   granted by `LevelUpRewardGranter` — this is the reveal, not the grant).
 *   Empty for levels that grant nothing; the section then renders nothing.
 */
@Composable
fun LevelUpCelebration(
    level: Int,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    rewards: List<LevelUpReward> = emptyList(),
) {
    val haptics = LocalHapticFeedback.current
    val inInspection = LocalInspectionMode.current
    val entrance = remember(level) { Animatable(if (inInspection) 1f else 0f) }

    LaunchedEffect(level) {
        if (inInspection) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque app background, not a scrim — this is a full-screen
            // takeover, so nothing should bleed through behind it.
            .background(AppTheme.colors.background.color),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimension.D1000)
                .graphicsLayer {
                    val e = entrance.value
                    alpha = e
                    scaleX = 0.85f + 0.15f * e
                    scaleY = 0.85f + 0.15f * e
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RotatingDial(
                size = 248.dp,
                rayColor = AppTheme.colors.accentSecondary,
                oddRayColor = AppTheme.colors.accentSecondaryDeep,
                glowColor = AppTheme.colors.accentSecondary,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(Res.string.ui_level_up_title),
                        typography = AppTheme.typography.Heading.H500,
                        color = AppTheme.colors.accentSecondary,
                        textAlign = TextAlign.Center,
                        allCaps = true,
                    )
                    Text(
                        text = level.toString(),
                        typography = AppTheme.typography.Display.D1400,
                        color = AppTheme.colors.content,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                text = stringResource(Res.string.ui_level_up_level, level),
                modifier = Modifier.padding(top = Dimension.D600),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.ui_level_up_message),
                modifier = Modifier.padding(top = Dimension.D300),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            if (rewards.isNotEmpty()) {
                RewardsPanel(
                    rewards = rewards,
                    modifier = Modifier.padding(top = Dimension.D700),
                )
            }
            ButtonPrimary(
                onClick = onContinue,
                accent = ButtonAccent.Secondary,
                modifier = Modifier
                    .padding(top = Dimension.D900)
                    .fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.ui_level_up_continue))
            }
        }
    }
}

/**
 * One revealed level-up prize, already mapped to display form by the caller
 * (the DS component stays out of the economy domain). [emoji] is the prize
 * glyph; [label] is the localized one-liner ("+5,000 chips", "XP Boost").
 */
data class LevelUpReward(
    val emoji: String,
    val label: String,
)

/**
 * The "You earned" card listing the level's prizes. A raised surface panel
 * over the scrim so the rewards read as a distinct, tangible payout rather
 * than more body copy.
 */
@Composable
private fun RewardsPanel(
    rewards: List<LevelUpReward>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surfaceRaised.color)
            .padding(horizontal = Dimension.D600, vertical = Dimension.D500),
        verticalArrangement = Arrangement.spacedBy(Dimension.D400),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.ui_level_up_rewards_header),
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.contentSecondary,
            allCaps = true,
        )
        rewards.forEach { reward ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = reward.emoji,
                    typography = AppTheme.typography.Heading.H600,
                )
                Text(
                    text = reward.label,
                    modifier = Modifier.padding(start = Dimension.D300),
                    typography = AppTheme.typography.Body.B600,
                    color = AppTheme.colors.content,
                )
            }
        }
    }
}

@Preview
@Composable
private fun LevelUpCelebrationPreview_SingleDigit() {
    PreviewContent {
        Box(Modifier.fillMaxSize().size(400.dp)) {
            LevelUpCelebration(level = 7, onContinue = {})
        }
    }
}

@Preview
@Composable
private fun LevelUpCelebrationPreview_DoubleDigit() {
    PreviewContent {
        Box(Modifier.fillMaxSize().size(400.dp)) {
            LevelUpCelebration(level = 24, onContinue = {})
        }
    }
}

@Preview
@Composable
private fun LevelUpCelebrationPreview_WithRewards() {
    PreviewContent {
        Box(Modifier.fillMaxSize().size(400.dp)) {
            LevelUpCelebration(
                level = 10,
                onContinue = {},
                rewards = listOf(
                    LevelUpReward(emoji = "🪙", label = "+7,500 chips"),
                    LevelUpReward(emoji = "⚡", label = "XP Boost"),
                ),
            )
        }
    }
}

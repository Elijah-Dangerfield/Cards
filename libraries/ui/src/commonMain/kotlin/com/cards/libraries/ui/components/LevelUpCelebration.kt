package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

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
 * 2026-06-06. The dial "slams in" (a quick anticipation dip, an overshoot past
 * full size, then a snap-and-settle) with a two-beat haptic (a light tick as it
 * launches, a heavy impact on the slam landing) and a confetti burst on arrival,
 * so the level-up lands in the hand as well as the eye.
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
    // Fade rides in fast; the slam owns the scale so it can dip, overshoot, then
    // settle. Both default to their landed values in inspection so previews
    // render the resting frame.
    val entrance = remember(level) { Animatable(if (inInspection) 1f else 0f) }
    val slam = remember(level) { Animatable(if (inInspection) 1f else 0.6f) }
    var confettiOn by remember(level) { mutableStateOf(inInspection) }

    LaunchedEffect(level) {
        if (inInspection) return@LaunchedEffect
        // Beat one: a light tick as the dial launches.
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        launch {
            entrance.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing),
            )
        }
        // The slam: anticipation dip → overshoot past full → snap back and let a
        // stiff bouncy spring settle the recoil.
        slam.animateTo(
            targetValue = 1.12f,
            animationSpec = keyframes {
                durationMillis = 340
                0.6f at 0
                0.52f at 70
                1.12f at 300
            },
        )
        // Beat two: the heavy impact as it lands, paired with the confetti.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        confettiOn = true
        slam.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
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
        // Top-anchored content with the dial + level number owning the upper
        // screen, and Continue pinned to the bottom edge via the weighted
        // spacer below — so the primary action sits in thumb reach instead of
        // floating directly under the rewards panel. safeDrawing insets keep
        // both ends off the system bars (this is a full-screen destination).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = Dimension.D1000)
                .graphicsLayer {
                    alpha = entrance.value
                    scaleX = slam.value
                    scaleY = slam.value
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            // Weighted top spacer drops the dial out of the very top edge into
            // roughly the top third — it reads less cramped than pinned up high.
            // Paired with the weight(1f) spacer above Continue (≈0.5 : 1 split).
            Spacer(modifier = Modifier.weight(0.5f))
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
            Spacer(modifier = Modifier.weight(1f))
            ButtonPrimary(
                onClick = onContinue,
                accent = ButtonAccent.Secondary,
                modifier = Modifier
                    .padding(bottom = Dimension.D500)
                    .fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.ui_level_up_continue))
            }
        }

        // Confetti fires the moment the dial slams home, raining over the whole
        // takeover (content draws under it, ignoring touches).
        if (confettiOn) {
            ConfettiBurst(modifier = Modifier.fillMaxSize())
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
                    LevelUpReward(emoji = "🎁", label = "New item"),
                ),
            )
        }
    }
}

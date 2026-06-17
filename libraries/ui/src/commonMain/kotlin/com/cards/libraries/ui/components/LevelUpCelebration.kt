package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_level_up_continue
import cards.libraries.resources.generated.resources.ui_level_up_level
import cards.libraries.resources.generated.resources.ui_level_up_message
import cards.libraries.resources.generated.resources.ui_level_up_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Full-screen, teal-identity celebration shown on Home when the user's
 * derived level crosses the [com.dangerfield.cards.libraries.cards.AppData]
 * `lastCelebratedLevel` watermark. A spinning [RotatingDial] burst re-skinned
 * in the level/XP teal frames the new level number, with a warm line and a
 * single Continue affordance.
 *
 * Deliberately a self-contained takeover (its own scrim, centered content)
 * rather than a [com.dangerfield.cards.libraries.ui.components.dialog.Dialog]:
 * the moment wants the whole screen. It only ever mounts on Home — never at
 * the poker table — per `docs/decisions.md` 2026-06-06. The entrance animates
 * (scale + fade in) and a heavy haptic fires as the dial lands so the level-up
 * registers in the hand as well as the eye.
 *
 * @param level the level just reached (the net level on a multi-level jump).
 * @param onContinue fires when the user taps Continue or the scrim; the host
 *   advances the watermark in response.
 */
@Composable
fun LevelUpCelebration(
    level: Int,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
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
            .background(AppTheme.colors.scrim.color)
            .pointerInput(Unit) { detectTapGestures { onContinue() } },
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

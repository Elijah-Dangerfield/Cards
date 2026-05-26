package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInQuad
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Plays the full "achievement unlocked" reveal sequence:
 *
 *   1. Mystery card lands and pauses briefly (anticipation).
 *   2. Card shakes left/right with intensifying amplitude. A light
 *      haptic tick fires on each peak so the rumble registers in the
 *      hand as well as the eye.
 *   3. Card bursts: scales up rapidly while fading out, surrounded by
 *      a radial spray of accent-color particle dots. Heavy haptic at
 *      ignition.
 *   4. The real medallion slams in from above with a squash-and-stretch
 *      spring rebound, lands with a second heavy haptic.
 *
 * Designed as a reusable DS primitive: any achievement unlock moment
 * (tutorial completion, level-up, first-bust, etc.) can drop this in.
 * Drives its own animation on first composition; pass a stable key
 * via `revealKey` to replay it (e.g. when the parent flow shows a
 * second unlock after the first).
 *
 * @param onSequenceComplete fires once the medallion has fully settled.
 *   Useful for unlocking a "Done" button so the user can't dismiss
 *   the celebration mid-animation.
 */
@Composable
fun AchievementUnlockReveal(
    achievement: Achievement,
    modifier: Modifier = Modifier,
    revealKey: Any = Unit,
    onSequenceComplete: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val accent = achievement.rarity.toAccentColor()

    // Phase animations. Each phase owns one Animatable so we can drive
    // them on a coroutine timeline inside a single LaunchedEffect.
    val mysteryAlpha = remember(revealKey) { Animatable(1f) }
    val mysteryScale = remember(revealKey) { Animatable(1f) }
    val shakeX = remember(revealKey) { Animatable(0f) }
    val burstProgress = remember(revealKey) { Animatable(0f) }
    val medallionAlpha = remember(revealKey) { Animatable(0f) }
    val medallionScale = remember(revealKey) { Animatable(2.2f) }

    LaunchedEffect(revealKey) {
        // ---- 1. Anticipation -----------------------------------------
        delay(220)

        // ---- 2. Shake -------------------------------------------------
        // Six oscillations with increasing amplitude. Sine-shaped via
        // discrete keyframes feels more "mechanical/tense" than a smooth
        // animation; achievement reveals want building tension, not
        // graceful glide.
        val shakeFrames = listOf(
            0f to 60,
            -4f to 60,
            6f to 60,
            -8f to 60,
            10f to 60,
            -12f to 60,
            0f to 80,
        )
        for ((offset, durationMs) in shakeFrames) {
            shakeX.animateTo(offset, tween(durationMs))
            // Tick on each peak. TextHandleMove is the lightest haptic
            // Compose exposes cross-platform; perfect for repeated
            // taps that shouldn't feel like a single fat thud.
            if (offset != 0f) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }

        // ---- 3. Burst -------------------------------------------------
        // Mystery card scales up + fades while particle field expands.
        // burstProgress runs 0→1; particles + ring derive from it.
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        launch {
            mysteryScale.animateTo(
                targetValue = 1.6f,
                animationSpec = tween(durationMillis = 240, easing = EaseOutQuad),
            )
        }
        launch {
            mysteryAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 240, easing = EaseOutQuad),
            )
        }
        burstProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = EaseOutQuad),
        )

        // ---- 4. Slam-in medallion ------------------------------------
        // Tiny gap so the burst clears before the medallion arrives.
        delay(60)
        launch {
            medallionAlpha.animateTo(1f, tween(durationMillis = 220))
        }
        medallionScale.animateTo(
            targetValue = 0.88f,
            animationSpec = tween(durationMillis = 220, easing = EaseInQuad),
        )
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        medallionScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )

        onSequenceComplete()
    }

    Box(
        modifier = modifier.aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // Particle field. Drawn behind the cards so they always land
        // on top. Particles only render during the burst window.
        if (burstProgress.value > 0f) {
            ParticleBurst(
                progress = burstProgress.value,
                color = accent,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Mystery card, fading + scaling out during the burst.
        if (mysteryAlpha.value > 0f) {
            MysteryCard(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = shakeX.value * density
                        scaleX = mysteryScale.value
                        scaleY = mysteryScale.value
                        alpha = mysteryAlpha.value
                    },
            )
        }

        // Real medallion. Hidden until reveal phase.
        if (medallionAlpha.value > 0f) {
            AchievementMedallion(
                achievement = achievement,
                // Mark earned so the medallion renders its "earned"
                // visuals. onClick = {} suppresses the flip-to-back so
                // the celebration moment isn't competing with a 3D
                // flip the user could accidentally trigger.
                earnedAtEpochMs = 1L,
                progress = achievement.criterion.target,
                onClick = {},
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = medallionScale.value
                        scaleY = medallionScale.value
                        alpha = medallionAlpha.value
                    },
            )
        }
    }
}

/**
 * Locked "?" card shown before the unlock. Visually distinct from the
 * AchievementMedallion's own mystery-locked state so the reveal feels
 * like a transformation rather than a status flip.
 */
@Composable
private fun MysteryCard(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(Radii.R900.shape)
            .background(AppTheme.colors.surfaceSecondary.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            typography = AppTheme.typography.Display.D1400,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Radial spray of dots drawn on a Canvas, used during the burst phase.
 * 12 dots fan out from center at evenly-spaced angles. Each dot's
 * distance from center and alpha are driven by [progress] (0→1).
 * Particles fade as they expand so the field reads as exploding
 * outward rather than just appearing everywhere.
 */
@Composable
private fun ParticleBurst(
    progress: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val particleCount = 12
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension * 0.55f
        val particleRadius = size.minDimension * 0.04f
        // Fade out across the back half of the burst so particles
        // dissolve rather than abruptly disappear.
        val alpha = if (progress < 0.5f) 1f else 1f - (progress - 0.5f) * 2f
        for (i in 0 until particleCount) {
            val angle = (i.toDouble() / particleCount) * 2 * PI
            val distance = maxRadius * progress
            val cx = (center.x + cos(angle).toFloat() * distance)
            val cy = (center.y + sin(angle).toFloat() * distance)
            drawCircle(
                color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
                radius = particleRadius,
                center = Offset(cx, cy),
            )
        }
    }
}

@Preview
@Composable
private fun AchievementUnlockRevealPreview() {
    PreviewContent {
        // Use the first achievement just to give the preview something
        // to render; the actual sequence is the focus.
        val achievement = com.dangerfield.cards.libraries.cards.AllAchievements.first()
        Box(
            modifier = Modifier
                .size(220.dp),
        ) {
            AchievementUnlockReveal(achievement = achievement)
        }
    }
}

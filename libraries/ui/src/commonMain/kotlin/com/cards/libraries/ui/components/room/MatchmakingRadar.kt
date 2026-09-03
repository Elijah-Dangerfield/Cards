package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.system.AppTheme

/**
 * The matchmaking "radar" — three teal rings pulsing outward on a stagger, a
 * static guide ring, a rotating conic sweep, and a bobbing blue medallion
 * holding a [ChipCoin] at the centre. The honest "we're looking for your
 * table" visual on the public Searching screen.
 *
 * [reduceMotion] is a hard requirement, not a nicety: when true (the platform
 * reduce-motion setting), every animation is dropped and the static end-state
 * renders instead. Pass the platform setting in from the feature layer so this
 * library composable stays platform-agnostic.
 */
@Composable
fun MatchmakingRadar(
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val teal = AppTheme.colors.accentSecondary.color
    val guide = AppTheme.colors.surfaceHigh.color
    val guideRingSize = size * 0.75f

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (reduceMotion) {
            Box(Modifier.size(size).border(1.5.dp, AppTheme.colors.accentSecondary.withAlpha(0.4f).color, CircleShape))
            Box(Modifier.size(guideRingSize).border(1.dp, guide, CircleShape))
            Medallion()
            return@Box
        }

        val transition = rememberInfiniteTransition(label = "radar")

        // Three rings expanding + fading, staggered by ~730ms.
        repeat(3) { i ->
            val progress = transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(i * 730),
                ),
                label = "ring$i",
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        val p = progress.value
                        val scale = 0.35f + p * 0.65f
                        scaleX = scale
                        scaleY = scale
                        alpha = (1f - p) * 0.55f
                    }
                    .border(1.5.dp, teal, CircleShape),
            )
        }

        // Static guide ring.
        Box(Modifier.size(guideRingSize).border(1.dp, guide, CircleShape))

        // Rotating conic sweep.
        val sweepAngle = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sweep",
        )
        Box(
            modifier = Modifier
                .size(guideRingSize)
                .graphicsLayer { rotationZ = sweepAngle.value }
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        0.0f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1.0f to teal.copy(alpha = 0.45f),
                    ),
                ),
        )

        // Bobbing medallion.
        val bob = transition.animateFloat(
            initialValue = -5f,
            targetValue = 5f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bob",
        )
        Medallion(modifier = Modifier.graphicsLayer { translationY = bob.value })
    }
}

@Composable
private fun Medallion(modifier: Modifier = Modifier) {
    val info = AppTheme.colors.info.color
    Box(
        modifier = modifier
            .size(86.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(info, lerp(info, Color.Black, 0.25f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        ChipCoin(size = 40.dp)
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun MatchmakingRadarPreview() {
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)) {
        MatchmakingRadar(reduceMotion = false)
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun MatchmakingRadarPreview_ReducedMotion() {
    PreviewContent(contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp)) {
        MatchmakingRadar(reduceMotion = true)
    }
}

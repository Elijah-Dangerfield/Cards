package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * A slowly-rotating sunburst "dial": tapered rays radiating outward from a
 * soft central glow, with a [content] slot pinned dead-center (e.g. a
 * [ChipCoin]). Reads as a radiant little sun behind whatever it frames.
 *
 * Built to be reused anywhere we want a celebratory, radiant focal point —
 * the onboarding starter-grant reveal is the first caller, but the rays,
 * glow, ray count, and spin period are all parameterized so other surfaces
 * (level-ups, jackpots, splash moments) can re-skin it.
 *
 * The rays spin on an infinite [rememberInfiniteTransition]. In @Preview /
 * inspection mode the rotation is pinned to a resting angle so design pins
 * render deterministically.
 */
@Composable
fun RotatingDial(
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    rayColor: ColorResource = AppTheme.colors.poker.chipGold,
    oddRayColor: ColorResource = AppTheme.colors.poker.chipGoldOutline,
    glowColor: ColorResource = AppTheme.colors.poker.seatActive,
    rayCount: Int = 12,
    periodMillis: Int = 28_000,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val rays = rayColor.color
    val oddRays = oddRayColor.color
    val glow = glowColor.color

    // Rays alternate between full-width primary rays and slightly thinner,
    // darker secondary rays. That alternation only reads evenly around the
    // circle when the count is even — an odd count lands two primaries
    // side-by-side at the wrap seam — so round up to the next even number.
    val evenRayCount = if (rayCount % 2 == 0) rayCount else rayCount + 1
    // State, read inside the Canvas below. This rotation never stops, so
    // unwrapping it here would recompose the dial at 60fps forever.
    val angle: State<Float> = if (LocalInspectionMode.current) {
        rememberUpdatedState(0f)
    } else {
        val transition = rememberInfiniteTransition(label = "rotating-dial")
        val animated = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(periodMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rotating-dial-angle",
        )
        animated
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            val center = this.center

            // Soft radial haze behind everything — the "sun" glow.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = 0.38f), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )

            // Rays: round-capped strokes from just outside the content slot to
            // near the edge, fanned evenly and spun as one rigid wheel. Odd
            // rays are thinner and use the darker [oddRayColor] so the burst
            // reads as alternating bold / fine spokes.
            val innerRadius = radius * 0.56f
            val outerRadius = radius * 0.94f
            val rayWidth = radius * 0.045f
            val oddRayWidth = rayWidth * 0.6f
            rotate(degrees = angle.value, pivot = center) {
                repeat(evenRayCount) { i ->
                    val isOdd = i % 2 == 1
                    rotate(degrees = i * (360f / evenRayCount), pivot = center) {
                        drawLine(
                            color = if (isOdd) oddRays else rays,
                            start = Offset(center.x, center.y - innerRadius),
                            end = Offset(center.x, center.y - outerRadius),
                            strokeWidth = if (isOdd) oddRayWidth else rayWidth,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
        content()
    }
}

@Preview
@Composable
private fun RotatingDialPreview() {
    PreviewContent {
        RotatingDial {
            ChipCoin(size = 96.dp)
        }
    }
}

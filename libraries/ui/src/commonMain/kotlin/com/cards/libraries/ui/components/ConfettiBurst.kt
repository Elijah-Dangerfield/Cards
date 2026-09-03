package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.tooling.preview.Preview

/**
 * A one-shot confetti burst that fires once on first composition: a spray of
 * paper rectangles launches up and out from a point near the top of the box,
 * spins, then falls under gravity and fades. Drawn on a single [Canvas] over
 * whatever it overlays — no per-particle composables — so a hundred pieces
 * cost one draw pass.
 *
 * Deliberately a DS primitive (not a per-screen effect) so every celebratory
 * "you earned it" moment — level-up, achievement unlock, big reveal — gets the
 * same paper feel for free. Colors come from theme tokens by default (chip
 * gold + the two accent identities), so a burst sits naturally on any surface.
 *
 * Self-limiting: runs [durationMillis] then stops at the last frame (pieces
 * have fallen off / faded), so it's safe to leave mounted. A no-op under
 * `LocalInspectionMode` so previews render the resting state.
 *
 * @param colors the paper palette; defaults to chip gold + both accents.
 * @param particleCount how many pieces to launch.
 */
@Composable
fun ConfettiBurst(
    modifier: Modifier = Modifier,
    colors: List<ColorResource> = listOf(
        AppTheme.colors.poker.chipGold,
        AppTheme.colors.accentSecondary,
        AppTheme.colors.accentPrimary,
    ),
    particleCount: Int = 90,
    durationMillis: Int = 2_400,
) {
    val inInspection = LocalInspectionMode.current
    val resolvedColors = colors.map { it.color }
    val pieces = remember(particleCount) { generatePieces(particleCount, resolvedColors) }
    // Freeze a representative mid-flight frame for previews; otherwise drive
    // the burst off the frame clock.
    var progress by remember { mutableFloatStateOf(if (inInspection) 0.4f else 0f) }

    LaunchedEffect(Unit) {
        if (inInspection) return@LaunchedEffect
        var start = 0L
        var now = 0f
        while (now < durationMillis) {
            withFrameMillis { frameMs ->
                if (start == 0L) start = frameMs
                now = (frameMs - start).toFloat()
            }
            progress = (now / durationMillis).coerceIn(0f, 1f)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (progress <= 0f) return@Canvas
        val originX = size.width / 2f
        val originY = size.height * 0.18f
        val gravity = size.height * 1.6f
        pieces.forEach { p ->
            val t = ((progress - p.delay) / (1f - p.delay).coerceAtLeast(0.0001f)).coerceAtLeast(0f)
            if (t <= 0f) return@forEach
            val x = originX + p.velocityX * size.width * t
            val y = originY + p.velocityY * size.height * t + 0.5f * gravity * t * t
            if (y > size.height + 40f) return@forEach
            val alpha = (1f - t).coerceIn(0f, 1f)
            val angle = p.spin * t * 360f + p.startAngle
            rotate(degrees = angle, pivot = Offset(x, y)) {
                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(x - p.width / 2f, y - p.height / 2f),
                    size = Size(p.width, p.height),
                )
            }
        }
    }
}

private class ConfettiPiece(
    val color: Color,
    val velocityX: Float,
    val velocityY: Float,
    val spin: Float,
    val startAngle: Float,
    val width: Float,
    val height: Float,
    val delay: Float,
)

private fun generatePieces(count: Int, palette: List<Color>): List<ConfettiPiece> {
    val random = Random(seed = 0x5EED)
    return List(count) {
        val angle = (random.nextFloat() * PI - PI / 2).toFloat()
        val speed = 0.45f + random.nextFloat() * 0.55f
        ConfettiPiece(
            color = palette[it % palette.size],
            velocityX = sin(angle) * speed,
            velocityY = -(0.35f + random.nextFloat() * 0.55f),
            spin = (if (random.nextBoolean()) 1f else -1f) * (1.5f + random.nextFloat() * 2.5f),
            startAngle = random.nextFloat() * 360f,
            width = 8f + random.nextFloat() * 8f,
            height = 14f + random.nextFloat() * 10f,
            delay = random.nextFloat() * 0.12f,
        )
    }
}

@Preview
@Composable
private fun ConfettiBurstPreview() {
    PreviewContent {
        Box(Modifier.size(360.dp).background(AppTheme.colors.background.color)) {
            ConfettiBurst()
        }
    }
}

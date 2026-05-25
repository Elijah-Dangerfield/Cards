package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * "Level N" progression pill. Sibling of [ChipBadge] — same chrome
 * via [LeadingPill], same 18dp leading footprint, so the two sit at
 * the same height when paired in a header.
 *
 * The leading element is a small gradient-filled circle wrapped by a
 * progress ring whose arc length encodes the fraction of XP earned
 * toward the next level. The user sees their level at a glance plus
 * an honest progress signal in the same pill — no separate XP bar
 * required.
 *
 * Visually replaces the old `XpBadge` (which surfaced raw lifetime
 * XP) — on most surfaces the level + progress is what matters and a
 * 7-digit XP number adds clutter without information.
 *
 * `onClick` typically routes to the Stats screen (the screen-of-record
 * for the full level / XP breakdown).
 */
@Composable
fun LevelPill(
    progress: LevelProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LeadingPill(
        text = "Level ${progress.level}",
        modifier = modifier,
        onClick = onClick,
        leading = { LevelRing(fraction = progress.fraction) },
    )
}

/**
 * Convenience overload — accepts raw lifetime XP and derives the
 * [LevelProgress] internally. Use when the caller already has xp as
 * a `Long` and would otherwise just immediately wrap it in
 * `levelProgressFor`.
 */
@Composable
fun LevelPill(
    xp: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LevelPill(progress = levelProgressFor(xp), onClick = onClick, modifier = modifier)
}

@Composable
private fun LevelRing(fraction: Float) {
    // Animate the arc so a fresh XP grant visibly fills the ring rather
    // than snapping. Short tween — Home / play screen are "snap into
    // context" surfaces, not celebratory ones.
    val animatedFraction by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "level-ring-fraction",
    )
    val ringColor = RING_HUE
    val trackColor = AppTheme.colors.surfaceTertiary.color

    Box(
        modifier = Modifier.size(RING_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(RING_SIZE)) {
            val strokePx = STROKE_WIDTH.toPx()
            val diameter = size.minDimension - strokePx
            val topLeft = Offset(
                x = (size.width - diameter) / 2f,
                y = (size.height - diameter) / 2f,
            )
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round),
            )
            if (animatedFraction > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(CIRCLE_SIZE)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4FC3F7), Color(0xFF66BB6A)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Canvas-drawn 4-point sparkle. Earlier iteration used a "✦"
            // Text glyph which sat visually low inside the 12dp circle —
            // the glyph's line-height box added padding that knocked it
            // off the center. Drawing it as a Path here puts the visual
            // bounds exactly under our control.
            SparkleGlyph(
                size = SPARKLE_SIZE,
                color = AppTheme.colors.text.color,
            )
        }
    }
}

@Composable
private fun SparkleGlyph(size: androidx.compose.ui.unit.Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        // Diamond-with-curved-sides 4-point sparkle. The inner control
        // points sit at 28% of the half-radius from center — gives the
        // tapered "pinch" look of a proper sparkle vs a flat plus sign.
        val inner = (w.coerceAtMost(h) / 2f) * 0.28f
        val path = Path().apply {
            moveTo(cx, 0f)
            lineTo(cx + inner, cy - inner)
            lineTo(w, cy)
            lineTo(cx + inner, cy + inner)
            lineTo(cx, h)
            lineTo(cx - inner, cy + inner)
            lineTo(0f, cy)
            lineTo(cx - inner, cy - inner)
            close()
        }
        drawPath(path = path, color = color)
    }
}

// Outer ring matches the 18dp footprint of [ChipCoin] (the leading
// element inside ChipBadge) so the two pills line up at the same
// height when paired together.
private val RING_SIZE = 18.dp
private val CIRCLE_SIZE = 12.dp
private val SPARKLE_SIZE = 7.dp
private val STROKE_WIDTH = 2.dp

/** Matches the cyan start of the inner gradient so the ring reads as
 *  the same family of colour as the fill it surrounds. Hardcoded
 *  because the surrounding gradient is too — a `PokerPalette` entry
 *  for "progression cyan" would let both lift off the literal,
 *  separate cleanup. */
private val RING_HUE = Color(0xFF4FC3F7)

// ---------------------------------------------------------------------------
// Previews — pin the visual across the states the pill will actually render:
// fresh user with almost no progress, mid-level, full ring (one XP grant
// away from levelling), and a high level number so the digits don't blow
// out the pill width.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun LevelPillPreview_FreshUser() {
    PreviewContent {
        LevelPill(progress = LevelProgress(level = 1, totalXp = 5, xpAtLevelStart = 0, xpForNextLevel = 100), onClick = {})
    }
}

@Preview
@Composable
private fun LevelPillPreview_MidLevel() {
    PreviewContent {
        LevelPill(progress = LevelProgress(level = 4, totalXp = 1_140, xpAtLevelStart = 1_000, xpForNextLevel = 1_600), onClick = {})
    }
}

@Preview
@Composable
private fun LevelPillPreview_OneXpFromLevelUp() {
    PreviewContent {
        LevelPill(progress = LevelProgress(level = 10, totalXp = 38_499, xpAtLevelStart = 28_500, xpForNextLevel = 10_000), onClick = {})
    }
}

@Preview
@Composable
private fun LevelPillPreview_TwoDigitLevel() {
    PreviewContent {
        LevelPill(progress = LevelProgress(level = 42, totalXp = 0, xpAtLevelStart = 0, xpForNextLevel = 176_400), onClick = {})
    }
}

package com.dangerfield.cards.libraries.ui.components

import com.dangerfield.cards.libraries.ui.system.color.ColorResource

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.system.LocalLevelCurve
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_level_pill_label
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

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
    boostExpiresAtEpochMs: Long? = null,
) {
    val levelText = stringResource(Res.string.ui_level_pill_label, progress.level)
    val boostRemainingMs = rememberBoostRemainingMs(boostExpiresAtEpochMs)
    if (boostRemainingMs > 0L) {
        // Boost is live — graft a teal "⚡ m:ss" countdown onto the trailing
        // slot so the running window is visible right on the play screen's pill.
        LeadingPill(
            modifier = modifier,
            onClick = onClick,
            leading = { XpBadge(fraction = progress.fraction) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = levelText,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.content,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "⚡ ${formatBoostCountdown(boostRemainingMs)}",
                        typography = AppTheme.typography.Body.B500.SemiBold,
                        color = AppTheme.colors.accentSecondary,
                    )
                }
            },
        )
    } else {
        LeadingPill(
            text = levelText,
            modifier = modifier,
            onClick = onClick,
            leading = { XpBadge(fraction = progress.fraction) },
        )
    }
}

/**
 * Convenience overload — accepts raw lifetime XP and derives the
 * [LevelProgress] internally against the [LocalLevelCurve] in force, so the
 * shown level honors a server-retuned curve. Use when the caller already has
 * xp as a `Long` and would otherwise just immediately wrap it in
 * `levelProgressFor`.
 */
@Composable
fun LevelPill(
    xp: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    boostExpiresAtEpochMs: Long? = null,
) {
    LevelPill(
        progress = levelProgressFor(xp, LocalLevelCurve.current),
        onClick = onClick,
        modifier = modifier,
        boostExpiresAtEpochMs = boostExpiresAtEpochMs,
    )
}

/**
 * Progression badge — gradient disc with a sparkle inside, wrapped by a
 * ring whose arc length encodes [fraction] (0..1) of XP earned toward
 * the next level.
 *
 * The leading element of [LevelPill]. Lifted out so onboarding /
 * explainer surfaces can render the same glyph at a larger size without
 * pulling the "Level N" text along.
 *
 * Sizes inside the badge scale proportionally to [size] using the same
 * ratios as the 18dp default: inner disc ~66%, sparkle ~39%, stroke
 * ~11%. Use the default for header pills; bump up to ~48dp for
 * explainer/marketing rows where the badge is the focal point.
 */
@Composable
fun XpBadge(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = RING_SIZE,
) {
    // Animate the arc so a fresh XP grant visibly fills the ring rather
    // than snapping. Short tween — Home / play screen are "snap into
    // context" surfaces, not celebratory ones.
    //
    // Skip the animation in previews so @Preview pins render at the
    // requested fraction instead of catching the ring mid-fill at 0.
    val target = fraction.coerceIn(0f, 1f)
    // State, read inside the Canvas lambda below. See RingedAvatar for why:
    // unwrapping here recomposes the pill on every frame of the 600ms fill, and
    // the pill sits in the play screen's top bar carrying the level number.
    val animatedFraction: State<Float> = if (LocalInspectionMode.current) {
        rememberUpdatedState(target)
    } else {
        animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 600),
            label = "level-ring-fraction",
        )
    }
    val ringColor = RING_HUE
    val trackColor = AppTheme.colors.surfaceHigh.color
    val circleSize = size * (12f / 18f)
    val sparkleSize = size * (7f / 18f)
    val strokeWidth = size * (2f / 18f)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = strokeWidth.toPx()
            val diameter = this.size.minDimension - strokePx
            val topLeft = Offset(
                x = (this.size.width - diameter) / 2f,
                y = (this.size.height - diameter) / 2f,
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
            val fill = animatedFraction.value
            if (fill > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fill,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(circleSize)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(AppTheme.colors.poker.progressionCyan.color, AppTheme.colors.poker.progressionGreen.color),
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
                size = sparkleSize,
                color = AppTheme.colors.content.color,
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

// Default outer ring matches the 18dp footprint of [ChipCoin] (the
// leading element inside ChipBadge) so the two pills line up at the
// same height when paired together. [XpBadge] callers can override
// for larger marketing / explainer surfaces.
private val RING_SIZE = 18.dp

/** Matches the cyan start of the inner gradient so the ring reads as
 *  the same family of colour as the fill it surrounds. */
private val RING_HUE = ColorResource.PokerProgressionCyan.color

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

// Pins the active-boost pill — the teal "⚡ m:ss" countdown grafted onto the
// trailing slot. Inspection mode pins the countdown to 1:30.
@Preview
@Composable
private fun LevelPillPreview_BoostActive() {
    PreviewContent {
        LevelPill(
            progress = LevelProgress(level = 4, totalXp = 1_140, xpAtLevelStart = 1_000, xpForNextLevel = 1_600),
            onClick = {},
            boostExpiresAtEpochMs = 1_000_000L,
        )
    }
}

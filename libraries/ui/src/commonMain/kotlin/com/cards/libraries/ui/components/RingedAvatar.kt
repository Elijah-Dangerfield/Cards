package com.dangerfield.cards.libraries.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.AppTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * An [AvatarCircle] wrapped in the same XP-progress ring the [LevelPill]
 * draws: a [trackColor][com.dangerfield.cards.system.AppTheme] track under a
 * cyan arc whose sweep encodes [fraction] (0..1) of XP earned toward the next
 * level. Lets a surface show "who you are + how far to the next level" in one
 * glyph — the Home header uses it in place of a separate level pill.
 *
 * The ring matches the level pill's treatment (cyan hue, rounded cap, 600ms
 * fill animation), scaled to the larger avatar footprint.
 */
@Composable
fun RingedAvatar(
    name: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 40.dp,
    emoji: String? = null,
    backgroundColorHex: String? = null,
) {
    val target = fraction.coerceIn(0f, 1f)
    // Skipped entirely in previews so @Preview pins render at the requested
    // fraction instead of catching the ring mid-fill — mirrors XpBadge.
    //
    // Either way it stays `State` and is read inside the Canvas lambda below,
    // never unwrapped here: a composition-scope read would recompose this
    // avatar on every frame of the 600ms fill, and the play screen draws one
    // per seat.
    val animatedFraction: State<Float> = if (LocalInspectionMode.current) {
        rememberUpdatedState(target)
    } else {
        animateFloatAsState(
            targetValue = target,
            animationSpec = tween(durationMillis = 600),
            label = "avatar-xp-ring",
        )
    }

    val ringColor = AppTheme.colors.accentSecondary.color
    val trackColor = AppTheme.colors.surfaceHigh.color
    val stroke = avatarSize * (3f / 40f)
    val gap = 2.dp
    val total = avatarSize + (stroke + gap) * 2

    Box(modifier = modifier.size(total), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(total)) {
            val strokePx = stroke.toPx()
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
        AvatarCircle(
            name = name,
            size = avatarSize,
            emoji = emoji,
            backgroundColorHex = backgroundColorHex,
            animationsEnabled = false,
        )
    }
}

@Preview
@Composable
private fun RingedAvatarPreview() {
    PreviewContent {
        RingedAvatar(
            name = "QuietAce72",
            fraction = 0.62f,
            emoji = "🦊",
            backgroundColorHex = "#E48A58",
        )
    }
}

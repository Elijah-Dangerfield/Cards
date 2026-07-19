package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.AppTheme
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Depleting circular ring drawn around an acting seat's avatar — the visible
 * "you're on the clock" countdown that mirrors the server's per-turn timer
 * ([com.dangerfield.cards.libraries.gameplay.RoomSettings.turnTimerSeconds]).
 * The arc sweeps from full to empty over [durationSeconds] so an auto-fold /
 * auto-check never lands without warning.
 *
 * The server stays authoritative on the actual timeout; this is purely a
 * local visual that re-arms whenever [turnKey] changes. Drive [turnKey] off
 * the turn token the seat is acting under — `handNumber to lastSequence` —
 * so it resets on every fresh action point (including a raise that returns
 * action to the same seat), exactly when the server re-arms its own delay.
 *
 * When a [deadlineEpochMs] is supplied (MP), the ring anchors to it and paints
 * the *real* time-left, so leaving the play screen and coming back resumes the
 * sweep instead of restarting it (MP-33). Without one (solo / preview) it falls
 * back to a full fixed-duration sweep keyed on [turnKey].
 *
 * Self-contained: callers render it only while a *human* seat is acting on a
 * timer-enforced table (bots act fast server-side and aren't timed). Caller
 * controls the size via the modifier — typically the same outer-ring size the
 * active-turn ring would occupy, so it bleeds around the avatar's edge.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun TurnCountdownRing(
    turnKey: Any?,
    durationSeconds: Int,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 3.dp,
    deadlineEpochMs: Long? = null,
) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(turnKey, durationSeconds, deadlineEpochMs) {
        val totalMs = durationSeconds.toLong() * 1_000L
        if (totalMs <= 0L) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        val remainingMs = deadlineEpochMs
            ?.let { (it - Clock.System.now().toEpochMilliseconds()).coerceIn(0L, totalMs) }
            ?: totalMs
        progress.snapTo((remainingMs.toFloat() / totalMs).coerceIn(0f, 1f))
        if (remainingMs > 0L) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = remainingMs.toInt(),
                    easing = LinearEasing,
                ),
            )
        }
    }
    val calm = AppTheme.colors.poker.seatActive.color
    val urgent = AppTheme.colors.danger.color
    val value = progress.value
    // Blend toward the danger color through the final stretch so the last few
    // seconds read as urgent. Fully calm above the threshold, fully urgent at 0.
    val arcColor = lerp(urgent, calm, (value / UrgentThreshold).coerceIn(0f, 1f))
    val trackColor = calm.copy(alpha = 0.18f)
    val stroke = with(LocalDensity.current) { strokeWidth.toPx() }
    Canvas(modifier = modifier) {
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = trackColor,
            startAngle = StartAngle,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = arcColor,
            startAngle = StartAngle,
            sweepAngle = -360f * value,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

private const val StartAngle = -90f
private const val UrgentThreshold = 0.25f

@Preview
@Composable
private fun TurnCountdownRingPreview() {
    PreviewContent {
        TurnCountdownRing(
            turnKey = 0,
            durationSeconds = 30,
            modifier = Modifier.size(64.dp),
        )
    }
}

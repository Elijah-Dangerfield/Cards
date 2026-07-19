package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.EmojiBlast
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

/**
 * Renders a one-shot table emote popping out of the **sender's avatar** and
 * floating up, replacing the old full-screen blast. Who reacted reads from where
 * the emote appears, so it needs no name label and never blankets the table. The
 * avatar bounds come from [TableRewardAnchors] (published by the opponents row +
 * player area); the local player's own blast ([emitterSeatIndex] null) resolves to
 * [humanSeatIndex]. A missing anchor (avatar not measured) falls back to clearing
 * the blast so its state can't stick.
 *
 * Reports [EmojiBlast.emittedAtEpochMs] back on completion so the caller clears the
 * exact blast it launched (guarding against a newer blast replacing it mid-flight).
 */
@Composable
internal fun SeatEmoteOverlay(
    blast: EmojiBlast?,
    emitterSeatIndex: Int?,
    humanSeatIndex: Int?,
    anchors: TableRewardAnchors,
    onComplete: (emittedAtEpochMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = blast ?: return
    var overlayOrigin by remember(current.emittedAtEpochMs) { mutableStateOf(Offset.Zero) }
    var overlaySize by remember(current.emittedAtEpochMs) { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                overlayOrigin = it.positionInRoot()
                overlaySize = it.size
            },
    ) {
        // Backstop: clear the blast even if the avatar never measures, so its state
        // can't stick non-null and block the next emote.
        LaunchedEffect(current.emittedAtEpochMs) {
            kotlinx.coroutines.delay(2_000)
            onComplete(current.emittedAtEpochMs)
        }
        if (overlaySize == IntSize.Zero) return@Box
        val targetIndex = emitterSeatIndex ?: humanSeatIndex
        val bounds = targetIndex?.let { anchors.seatAvatarBounds[it] } ?: return@Box
        val anchor = bounds.center - overlayOrigin
        // Your own emote drifts straight up (reads best from your own seat). An
        // opponent's travels from their avatar toward the table centre so it's
        // noticeable to everyone else, not tucked at the top of the screen.
        val isLocalEmote = targetIndex == humanSeatIndex
        val overlayCenter = Offset(overlaySize.width / 2f, overlaySize.height / 2f)

        val progress = remember(current.emittedAtEpochMs) { Animatable(0f) }
        LaunchedEffect(current.emittedAtEpochMs) {
            progress.animateTo(1f, tween(durationMillis = 1_800, easing = LinearOutSlowInEasing))
            onComplete(current.emittedAtEpochMs)
        }

        val density = LocalDensity.current
        val liftPx = with(density) { 28.dp.toPx() }
        val risePx = with(density) { 52.dp.toPx() }
        Box(
            modifier = Modifier.graphicsLayer {
                val p = progress.value
                // Pop in (0.5 → 1) over the first quarter, then hold.
                val s = 0.5f + 0.5f * (p / 0.25f).coerceIn(0f, 1f)
                scaleX = s
                scaleY = s
                if (isLocalEmote) {
                    translationX = anchor.x - size.width / 2f
                    // Sit just above the avatar and drift further up as it fades.
                    translationY = anchor.y - size.height / 2f - liftPx - risePx * p
                } else {
                    // Travel most of the way to the table centre; it fades as it
                    // arrives, so it draws the eye without blanketing the board.
                    val pos = lerp(anchor, overlayCenter, 0.7f * p)
                    translationX = pos.x - size.width / 2f
                    translationY = pos.y - size.height / 2f - liftPx
                }
                alpha = when {
                    p < 0.12f -> p / 0.12f
                    p > 0.65f -> ((1f - p) / 0.35f).coerceIn(0f, 1f)
                    else -> 1f
                }
            },
        ) {
            Text(
                text = current.emoji,
                typography = AppTheme.typography.Display.D1200,
                color = AppTheme.colors.content,
            )
        }
    }
}

package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Pulsing circular halo behind the active player's avatar — the canonical
 * "your turn" / "this seat is acting" signal. Self-contained: drives its own
 * alpha animation, so callers only render it when the seat is actually acting.
 *
 * Caller controls the size via the modifier (typically a few dp larger than
 * the avatar itself so the halo bleeds around the avatar's outer edge).
 */
@Composable
fun PulsingActiveRing(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "active-pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.78f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(PokerPalette.SeatActive.copy(alpha = alpha)),
    )
}

@Preview
@Composable
private fun PulsingActiveRingPreview() {
    PreviewContent {
        PulsingActiveRing(modifier = Modifier.size(64.dp))
    }
}

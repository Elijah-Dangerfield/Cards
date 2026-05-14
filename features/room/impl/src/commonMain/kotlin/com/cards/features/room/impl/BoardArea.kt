package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSlot
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.delay

@Composable
internal fun BoardArea(table: TableUiState.Active) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Five community cards, overlapping. Later cards render on top of earlier
        // ones — since rank+suit are on the left edge of each card, the right
        // edge being clipped by the next card is fine.
        Row(
            horizontalArrangement = Arrangement.spacedBy((-28).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0 until 5) {
                val c = table.communityCards.getOrNull(i)
                val streetIndexInStreet = if (i < 3) i else 0
                BoardCard(
                    card = c,
                    slotIndex = i,
                    revealDelayMs = streetIndexInStreet * 240,
                    size = PlayingCardSize.Board,
                )
            }
        }
        if (table.pot > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Pot ${table.pot}",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.textSecondary,
            )
        }
        // Showdown is rendered as a modal sheet so it doesn't fight the table
        // layout for vertical space — see ShowdownDialog at the screen root.
    }
}

@Composable
private fun BoardCard(card: Card?, slotIndex: Int, revealDelayMs: Int, size: PlayingCardSize) {
    if (card == null) {
        PlayingCardSlot(size = size)
        return
    }
    // Compose previews don't drive animations to completion — without this the
    // card would render mid-flight (translated up, half-flipped). In preview,
    // jump straight to the settled face-up state.
    val inPreview = LocalInspectionMode.current
    key(card) {
        var arrived by remember { mutableStateOf(inPreview) }
        var revealed by remember { mutableStateOf(inPreview) }
        var settled by remember { mutableStateOf(inPreview) }
        if (!inPreview) {
            LaunchedEffect(Unit) {
                delay(revealDelayMs.toLong())
                arrived = true
                delay(340)
                revealed = true
                delay(420)
                settled = true
            }
        }
        if (settled) {
            // Animation finished — render plain, no graphicsLayer.
            PlayingCard(card = card, size = size)
        } else {
            val flightDp = -120f
            val flightPx = with(LocalDensity.current) { flightDp.dp.toPx() }
            val translationY by animateFloatAsState(
                targetValue = if (arrived) 0f else flightPx,
                animationSpec = tween(340, easing = FastOutSlowInEasing),
                label = "board-fly",
            )
            val rotation by animateFloatAsState(
                targetValue = if (revealed) 180f else 0f,
                animationSpec = tween(380),
                label = "board-flip",
            )
            Box(
                modifier = Modifier
                    .size(width = size.width, height = size.height)
                    .graphicsLayer {
                        this.translationY = translationY
                        rotationY = rotation
                        cameraDistance = 12f * density
                    },
            ) {
                if (rotation <= 90f) {
                    PlayingCardBack(size = size)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f },
                    ) {
                        PlayingCard(card = card, size = size)
                    }
                }
            }
        }
    }
}

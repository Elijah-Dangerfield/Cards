package com.dangerfield.cards.features.room.impl

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.poker.LocalFeltAccentSurface
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardBack
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD600
import kotlinx.coroutines.delay
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun BoardArea(table: TableUiState.Active, onPotClick: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        val cardSize = PlayingCardSize.Board
        val overlap = 28.dp
        // Five community cards, overlapping. A single outlined well sits behind
        // the row spanning the full 5-slot region — dealt cards cover their
        // portion of the well, undealt portions read as one unified "cards land
        // here" zone instead of five disconnected outlines.
        Box(contentAlignment = Alignment.Center) {
            BoardWell(
                width = cardSize.width * 5 - overlap * 4,
                height = cardSize.height,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(-overlap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (i in 0 until 5) {
                    val c = table.communityCards.getOrNull(i)
                    val streetIndexInStreet = if (i < 3) i else 0
                    if (c == null) {
                        Spacer(modifier = Modifier.size(width = cardSize.width, height = cardSize.height))
                    } else {
                        BoardCard(
                            card = c,
                            revealDelayMs = streetIndexInStreet * 240,
                            size = cardSize,
                        )
                    }
                }
            }
        }
        if (table.pot > 0) {
            VerticalSpacerD600()
            PotPill(amount = table.pot, onClick = onPotClick)
        }
    }
}

@Composable
private fun BoardWell(width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(Radii.R700.shape)
            .border(
                width = 1.5.dp,
                color = PokerPalette.CardSlotOutline,
                shape = Radii.R700.shape,
            ),
    )
}

@Composable
private fun BoardCard(card: Card, revealDelayMs: Int, size: PlayingCardSize) {
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

@Composable
private fun PotPill(amount: Long, onClick: () -> Unit) {
    val pillBackground = LocalFeltAccentSurface.current ?: AppTheme.colors.surfaceSecondary.color
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(pillBackground)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(Radii.Round.shape)
                .background(PokerPalette.ChipGold),
        )
        Text(
            text = "POT",
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.onSurfaceSecondary,
        )
        Text(
            text = formatCompactChips(amount),
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.onSurfacePrimary,
        )
    }
}

@Preview
@Composable
private fun BoardAreaPreview_Preflop() {
    PreviewContent {
        BoardArea(table = PreviewSamples.activeTable(pot = 30))
    }
}

@Preview
@Composable
private fun BoardAreaPreview_Flop() {
    PreviewContent {
        BoardArea(
            table = PreviewSamples.activeTable(
                street = BettingRound.Flop,
                communityCards = PreviewSamples.flopBoard(),
                pot = 120,
            ),
        )
    }
}

@Preview
@Composable
private fun BoardAreaPreview_Turn() {
    PreviewContent {
        BoardArea(
            table = PreviewSamples.activeTable(
                street = BettingRound.Turn,
                communityCards = PreviewSamples.turnBoard(),
                pot = 320,
            ),
        )
    }
}

@Preview
@Composable
private fun BoardAreaPreview_River_BigPot() {
    PreviewContent {
        BoardArea(
            table = PreviewSamples.activeTable(
                street = BettingRound.River,
                communityCards = PreviewSamples.riverBoard(),
                pot = 1_840,
            ),
        )
    }
}

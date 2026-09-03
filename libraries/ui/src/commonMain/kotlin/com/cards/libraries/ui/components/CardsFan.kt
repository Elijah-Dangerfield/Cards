package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import androidx.compose.ui.tooling.preview.Preview

/**
 * A fanned stack of four face cards — A♠ K♥ Q♦ J♣ — rendered via
 * [PlayingCard] so it inherits the same shadow and chrome as cards on
 * the table.
 *
 * [fanProgress] (0..1) interpolates from "stacked dead-center" to
 * "fully fanned". The same composable serves the welcome screen (held
 * at 1f) and the splash loader (animating 0 → 1 → 0 on loop).
 *
 * Rotation is the conventional ±10° / ±22° splay used elsewhere in the
 * brand. Translation is a fraction of card width so the fan still
 * reads at smaller card sizes.
 */
@Composable
fun CardsFan(
    modifier: Modifier = Modifier,
    fanProgress: Float = 1f,
    cardSize: PlayingCardSize = DEFAULT_FAN_SIZE,
) {
    // Overlap between adjacent cards as a fraction of width — keeps the fan
    // visually proportional whether it's rendered at deck-size or splash-size.
    val overlapFraction = 28f / 64f
    val overlap = cardSize.width * overlapFraction
    val totalWidth = cardSize.width + overlap * 3
    Box(
        modifier = modifier.size(width = totalWidth + 24.dp, height = cardSize.height + 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        FAN_CARDS.forEachIndexed { index, card ->
            val angle = FAN_ANGLES[index] * fanProgress
            PlayingCard(
                card = card,
                size = cardSize,
                modifier = Modifier.graphicsLayer {
                    rotationZ = angle
                    translationX = (index - 1.5f) * overlap.toPx() * fanProgress
                },
            )
        }
    }
}

// Derive the width from the canonical card ratio (don't hardcode it) so the fan
// reads as the same shape as the cards on the felt. Height is preserved.
private val DEFAULT_FAN_SIZE = PlayingCardSize.ofHeight(112.dp)

private val FAN_CARDS = listOf(
    Card(Rank.Ace, Suit.Spades),
    Card(Rank.King, Suit.Hearts),
    Card(Rank.Queen, Suit.Diamonds),
    Card(Rank.Jack, Suit.Clubs),
)

private val FAN_ANGLES = listOf(-22f, -10f, 10f, 22f)

@Preview
@Composable
private fun CardsFanPreview_Fanned() {
    PreviewContent {
        CardsFan(fanProgress = 1f)
    }
}

@Preview
@Composable
private fun CardsFanPreview_Stacked() {
    PreviewContent {
        CardsFan(fanProgress = 0f)
    }
}

@Preview
@Composable
private fun CardsFanPreview_Splash() {
    PreviewContent {
        CardsFan(
            fanProgress = 1f,
            cardSize = PlayingCardSize.ofHeight(124.dp),
        )
    }
}

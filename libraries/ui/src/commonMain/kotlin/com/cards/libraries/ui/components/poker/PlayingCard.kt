package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.typography.TypographyResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Canonical sizes for playing-card rendering. Custom sizes are fine but pick
 * one of these whenever possible so the look stays consistent across screens
 * (table, showdown, cheat sheet).
 */
data class PlayingCardSize(val width: Dp, val height: Dp) {
    companion object {
        /** Smallest readable card. Cheat-sheet examples and seat indicators. */
        val Mini = PlayingCardSize(30.dp, 40.dp)

        /** A deck-stack card. */
        val Deck = PlayingCardSize(36.dp, 50.dp)

        /** Community board card. */
        val Board = PlayingCardSize(92.dp, 126.dp)

        /** A player's own hole card. */
        val Hole = PlayingCardSize(104.dp, 142.dp)
    }
}

/**
 * A face-up playing card. Rank in the top-left, suit symbol in the bottom-left
 * (poker convention so it's readable when cards overlap from the right).
 */
@Composable
fun PlayingCard(
    card: Card,
    size: PlayingCardSize,
    modifier: Modifier = Modifier,
    rankTypography: TypographyResource? = null,
    suitTypography: TypographyResource? = null,
) {
    val width = size.width
    val height = size.height
    val isRed = card.suit == Suit.Hearts || card.suit == Suit.Diamonds
    val resolvedRankType = rankTypography ?: defaultRankTypography(width)
    val resolvedSuitType = suitTypography ?: defaultSuitTypography(width)
    val color = if (isRed) AppTheme.colors.danger else AppTheme.colors.background
    val cornerRadius = cornerRadiusFor(width)
    val padding = paddingFor(width)
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .shadow(shadowElevation(width), RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
            .background(PokerPalette.CardWhite)
            .padding(horizontal = padding, vertical = padding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = card.rank.display,
                typography = resolvedRankType,
                color = color,
                textAlign = TextAlign.Start,
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomStart) {
                Text(
                    text = card.suit.symbol,
                    typography = resolvedSuitType,
                    color = color,
                )
            }
        }
    }
}

/**
 * A face-down card — back of deck. Used for community cards not yet
 * dealt and opponent hole cards.
 *
 * Style is read from the ambient [LocalCardBackStyle] (default = the
 * stock blue back). Set once at the play-screen root via
 * [androidx.compose.runtime.CompositionLocalProvider] so every face-down
 * card in the composition picks up the equipped style automatically.
 *
 * Solid background by design — the back must obscure whatever's behind
 * it (table felt, slot well) regardless of style.
 */
@Composable
fun PlayingCardBack(
    size: PlayingCardSize,
    modifier: Modifier = Modifier,
    style: CardBackStyle = LocalCardBackStyle.current,
) {
    val cornerRadius = cornerRadiusFor(size.width)
    val palette = paletteFor(style)
    Box(
        modifier = modifier
            .size(width = size.width, height = size.height)
            .shadow(shadowElevation(size.width) - 1.dp, RoundedCornerShape(cornerRadius))
            .clip(RoundedCornerShape(cornerRadius))
            .background(palette.baseBrush)
            .border(1.dp, palette.borderColor, RoundedCornerShape(cornerRadius)),
    )
}

/** An empty placeholder slot. Pairs with [PlayingCard] and [PlayingCardBack] for not-yet-dealt positions. */
@Composable
fun PlayingCardSlot(
    size: PlayingCardSize,
    modifier: Modifier = Modifier,
) {
    val cornerRadius = cornerRadiusFor(size.width)
    Box(
        modifier = modifier
            .size(width = size.width, height = size.height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(PokerPalette.CardSlot),
    )
}

// ----- Internal style helpers -----------------------------------------------

@Composable
private fun defaultRankTypography(width: Dp): TypographyResource = when {
    width >= 90.dp -> AppTheme.typography.Heading.H1000
    width >= 60.dp -> AppTheme.typography.Heading.H800
    width >= 40.dp -> AppTheme.typography.Body.B700
    else -> AppTheme.typography.Body.B600
}

@Composable
private fun defaultSuitTypography(width: Dp): TypographyResource = when {
    width >= 90.dp -> AppTheme.typography.Heading.H1000
    width >= 60.dp -> AppTheme.typography.Body.B600
    else -> AppTheme.typography.Body.B500
}

private fun cornerRadiusFor(width: Dp): Dp = when {
    width >= 70.dp -> 12.dp
    width >= 40.dp -> 8.dp
    else -> 6.dp
}

private fun paddingFor(width: Dp): Dp = when {
    width >= 70.dp -> 8.dp
    width >= 40.dp -> 4.dp
    else -> 3.dp
}

private fun shadowElevation(width: Dp): Dp = when {
    width >= 70.dp -> 6.dp
    width >= 40.dp -> 4.dp
    else -> 2.dp
}

// ----- Previews -------------------------------------------------------------

@Preview
@Composable
private fun PlayingCardPreview_AllSizes() {
    PreviewContent {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Ace, Suit.Spades), PlayingCardSize.Mini)
                PlayingCard(Card(Rank.King, Suit.Hearts), PlayingCardSize.Mini)
                PlayingCard(Card(Rank.Ten, Suit.Diamonds), PlayingCardSize.Mini)
                PlayingCardBack(PlayingCardSize.Mini)
                PlayingCardSlot(PlayingCardSize.Mini)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Queen, Suit.Clubs), PlayingCardSize.Deck)
                PlayingCardBack(PlayingCardSize.Deck)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Ace, Suit.Hearts), PlayingCardSize.Board)
                PlayingCardBack(PlayingCardSize.Board)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayingCard(Card(Rank.Jack, Suit.Spades), PlayingCardSize.Hole)
                PlayingCard(Card(Rank.Jack, Suit.Hearts), PlayingCardSize.Hole)
            }
        }
    }
}

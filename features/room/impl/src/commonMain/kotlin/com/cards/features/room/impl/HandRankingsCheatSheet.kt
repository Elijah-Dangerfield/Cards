package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

private data class RankingEntry(
    val name: String,
    val tagline: String,
    val cards: List<Card>,
)

private val rankings: List<RankingEntry> = listOf(
    RankingEntry(
        name = "Royal Flush",
        tagline = "10, J, Q, K, A — all same suit",
        cards = listOf(
            Card(Rank.Ten, Suit.Spades),
            Card(Rank.Jack, Suit.Spades),
            Card(Rank.Queen, Suit.Spades),
            Card(Rank.King, Suit.Spades),
            Card(Rank.Ace, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = "Straight Flush",
        tagline = "Five in a row, same suit",
        cards = listOf(
            Card(Rank.Five, Suit.Hearts),
            Card(Rank.Six, Suit.Hearts),
            Card(Rank.Seven, Suit.Hearts),
            Card(Rank.Eight, Suit.Hearts),
            Card(Rank.Nine, Suit.Hearts),
        ),
    ),
    RankingEntry(
        name = "Four of a Kind",
        tagline = "Four cards of the same rank",
        cards = listOf(
            Card(Rank.Jack, Suit.Spades),
            Card(Rank.Jack, Suit.Hearts),
            Card(Rank.Jack, Suit.Diamonds),
            Card(Rank.Jack, Suit.Clubs),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = "Full House",
        tagline = "Three of a kind + a pair",
        cards = listOf(
            Card(Rank.Queen, Suit.Spades),
            Card(Rank.Queen, Suit.Hearts),
            Card(Rank.Queen, Suit.Diamonds),
            Card(Rank.Five, Suit.Clubs),
            Card(Rank.Five, Suit.Hearts),
        ),
    ),
    RankingEntry(
        name = "Flush",
        tagline = "Five cards of the same suit",
        cards = listOf(
            Card(Rank.Ace, Suit.Diamonds),
            Card(Rank.Jack, Suit.Diamonds),
            Card(Rank.Eight, Suit.Diamonds),
            Card(Rank.Five, Suit.Diamonds),
            Card(Rank.Three, Suit.Diamonds),
        ),
    ),
    RankingEntry(
        name = "Straight",
        tagline = "Five in a row, any suits",
        cards = listOf(
            Card(Rank.Five, Suit.Spades),
            Card(Rank.Six, Suit.Diamonds),
            Card(Rank.Seven, Suit.Clubs),
            Card(Rank.Eight, Suit.Hearts),
            Card(Rank.Nine, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = "Three of a Kind",
        tagline = "Three cards of the same rank",
        cards = listOf(
            Card(Rank.Seven, Suit.Spades),
            Card(Rank.Seven, Suit.Hearts),
            Card(Rank.Seven, Suit.Clubs),
            Card(Rank.King, Suit.Diamonds),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = "Two Pair",
        tagline = "Two pairs of different ranks",
        cards = listOf(
            Card(Rank.Ace, Suit.Spades),
            Card(Rank.Ace, Suit.Hearts),
            Card(Rank.Eight, Suit.Clubs),
            Card(Rank.Eight, Suit.Diamonds),
            Card(Rank.Three, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = "Pair",
        tagline = "Two cards of the same rank",
        cards = listOf(
            Card(Rank.Ten, Suit.Spades),
            Card(Rank.Ten, Suit.Hearts),
            Card(Rank.Queen, Suit.Clubs),
            Card(Rank.Six, Suit.Diamonds),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = "High Card",
        tagline = "Best single card wins",
        cards = listOf(
            Card(Rank.Ace, Suit.Spades),
            Card(Rank.Jack, Suit.Hearts),
            Card(Rank.Eight, Suit.Clubs),
            Card(Rank.Five, Suit.Diamonds),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
)

@Composable
fun HandRankingsCheatSheet(
    onDismiss: () -> Unit,
    handNumber: Int? = null,
    street: BettingRound? = null,
) {
    BottomSheet(
        onDismissRequest = onDismiss,
        showDragHandle = true,
        backgroundColor = AppTheme.colors.surfacePrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // Header
            Text(
                text = "Hand rankings",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            if (handNumber != null || street != null) {
                Spacer(modifier = Modifier.height(2.dp))
                val pieces = listOfNotNull(
                    handNumber?.let { "Hand #$it" },
                    street?.let { streetLabelForCheatSheet(it) },
                )
                Text(
                    text = pieces.joinToString(" · "),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Strongest on top.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))

            rankings.forEach { entry ->
                RankingCard(entry = entry)
                Spacer(modifier = Modifier.height(8.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RankingCard(entry: RankingEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column {
            Text(
                text = entry.name,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Text(
                text = entry.tagline,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            entry.cards.forEach { PlayingCard(card = it, size = PlayingCardSize.Mini) }
        }
    }
}

private fun streetLabelForCheatSheet(street: BettingRound): String = when (street) {
    BettingRound.Preflop -> "Preflop"
    BettingRound.Flop -> "Flop"
    BettingRound.Turn -> "Turn"
    BettingRound.River -> "River"
    BettingRound.Showdown -> "Showdown"
    BettingRound.Complete -> "Hand complete"
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun HandRankingsCheatSheetPreview_MidHand() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        HandRankingsCheatSheet(onDismiss = {}, handNumber = 3, street = BettingRound.Flop)
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun HandRankingsCheatSheetPreview_NoHandInfo() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        HandRankingsCheatSheet(onDismiss = {})
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun HandRankingsCheatSheetPreview_River() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        HandRankingsCheatSheet(onDismiss = {}, handNumber = 17, street = BettingRound.River)
    }
}

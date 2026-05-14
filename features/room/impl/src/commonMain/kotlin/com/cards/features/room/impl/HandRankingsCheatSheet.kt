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
    pot: Long? = null,
) {
    BottomSheet(
        onDismissRequest = onDismiss,
        showDragHandle = false,
        backgroundColor = AppTheme.colors.surfacePrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            // "Current hand" hero — explains what street we're on for players
            // who tapped the (?) because they're confused, not because they
            // forgot what beats what.
            if (street != null) {
                CurrentHandCard(
                    handNumber = handNumber,
                    street = street,
                    pot = pot,
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            Text(
                text = "Hand rankings",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Strongest on top.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            Spacer(modifier = Modifier.height(20.dp))

            rankings.forEach { entry ->
                RankingCard(entry = entry)
                Spacer(modifier = Modifier.height(12.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CurrentHandCard(
    handNumber: Int?,
    street: BettingRound,
    pot: Long?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = streetLabelForCheatSheet(street),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (pot != null && pot > 0) {
                Text(
                    text = "Pot $pot",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                )
            }
        }
        if (handNumber != null) {
            Text(
                text = "Hand #$handNumber",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
        Text(
            text = streetExplainer(street),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfacePrimary,
        )
    }
}

private fun streetExplainer(street: BettingRound): String = when (street) {
    BettingRound.Preflop ->
        "Two cards dealt face-down to each player. Blinds are posted; players act starting left of the big blind. Fold, call, or raise."
    BettingRound.Flop ->
        "Three community cards revealed. Combine any two of your hole cards with the board (or play the board) to make your best 5-card hand. A new round of betting begins."
    BettingRound.Turn ->
        "Fourth community card on the board. One more round of betting before the river — bets typically get bigger here."
    BettingRound.River ->
        "Fifth and final community card. One last round of betting, then any remaining players show their cards."
    BettingRound.Showdown ->
        "All remaining players reveal their hands. Best 5-card hand using any combination of hole and community cards wins the pot."
    BettingRound.Complete ->
        "Hand finished — chips are being awarded. Tap \"Next hand\" to play again."
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
        HandRankingsCheatSheet(
            onDismiss = {},
            handNumber = 3,
            street = BettingRound.Flop,
            pot = 240,
        )
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
        HandRankingsCheatSheet(
            onDismiss = {},
            handNumber = 17,
            street = BettingRound.River,
            pot = 1_240,
        )
    }
}

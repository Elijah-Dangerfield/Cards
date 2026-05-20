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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.components.dialog.LowLevelDialogApi
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BaseBottomSheet
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD1000
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD50
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD900

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

@OptIn(LowLevelDialogApi::class)
@Composable
fun HandRankingsCheatSheet(
    onDismiss: () -> Unit,
    handNumber: Int? = null,
    street: BettingRound? = null,
    pot: Long? = null,
) {
    BaseBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle.None,
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
                VerticalSpacerD900()
            }

            Text(
                text = "How to act",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD600()
            ActionRow(
                symbol = "✓",
                title = "Check",
                desc = "Pass to the next player without betting.",
                accent = ColorResource.Green600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "=",
                title = "Call",
                desc = "Match the current bet to stay in the hand.",
                accent = ColorResource.Blue600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "↑",
                title = "Raise",
                desc = "Increase the current bet. Everyone else must call, raise, or fold.",
                accent = ColorResource.Orange600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "✕",
                title = "Fold",
                desc = "Give up the hand. Any chips already in the pot stay.",
                accent = ColorResource.Red600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "★",
                title = "All in",
                desc = "Push your entire stack. If you win, you win up to what everyone matched.",
                accent = ColorResource.Purple600,
            )

            VerticalSpacerD1000()
            Text(
                text = "Hand rankings",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD200()
            Text(
                text = "Strongest on top.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            VerticalSpacerD800()

            rankings.forEach { entry ->
                RankingCard(entry = entry)
                VerticalSpacerD500()
            }
            VerticalSpacerD300()
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
            .clip(RoundedCornerShape(28.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (handNumber != null) {
                HandNumberPill(handNumber = handNumber)
            }
            Spacer(modifier = Modifier.weight(1f))
            StreetProgress(current = street)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = streetLabelForCheatSheet(street),
                typography = AppTheme.typography.Heading.H900,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (pot != null && pot > 0) {
                ChipPill(amount = pot)
            }
        }

        Text(
            text = streetExplainer(street),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
        )
    }
}

@Composable
private fun HandNumberPill(handNumber: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.surfaceTertiary.color)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = "HAND #$handNumber",
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.onSurfaceSecondary,
        )
    }
}

private val progressStages = listOf(
    BettingRound.Preflop,
    BettingRound.Flop,
    BettingRound.Turn,
    BettingRound.River,
    BettingRound.Showdown,
)

@Composable
private fun StreetProgress(current: BettingRound) {
    // Map Complete back onto Showdown for progress purposes — once the hand's
    // resolved the journey is "all the way through."
    val currentIndex = when (current) {
        BettingRound.Complete -> progressStages.lastIndex
        else -> progressStages.indexOf(current).coerceAtLeast(0)
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        progressStages.forEachIndexed { index, _ ->
            val isCurrent = index == currentIndex
            val isPast = index < currentIndex
            val color = when {
                isCurrent -> AppTheme.colors.accentPrimary.color
                isPast -> AppTheme.colors.onSurfaceSecondary.color
                else -> AppTheme.colors.surfaceTertiary.color
            }
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (isCurrent) 22.dp else 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
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
private fun ActionRow(
    symbol: String,
    title: String,
    desc: String,
    accent: ColorResource,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(50))
                .background(accent.color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = symbol,
                typography = AppTheme.typography.Heading.H600,
                color = accent,
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD50()
            Text(
                text = desc,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
    }
}

@Composable
private fun RankingCard(entry: RankingEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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

package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme

/**
 * Hand-end modals shown when the human's hand finishes — either a normal
 * showdown summary or, when their stack hit 0, a focused bust-recovery
 * moment. The auto-rebuy itself happens silently in `LocalBotsSession`;
 * these dialogs just make the moment legible.
 */

@Composable
internal fun ShowdownDialog(
    result: HandResultView,
    seats: List<SeatView>,
    xpEarned: Int?,
    onNextHand: () -> Unit,
) {
    val winnerIndices = result.winners.map { it.seatIndex }.toSet()
    val humanSeat = seats.firstOrNull { it.isHuman }
    val humanWon = humanSeat != null && humanSeat.index in winnerIndices
    val humanWinAmount = humanSeat
        ?.let { hs -> result.winners.filter { it.seatIndex == hs.index }.sumOf { it.amount } }
        ?: 0L
    val byFold = result.winners.all { it.byFold }
    val totalPot = result.winners.sumOf { it.amount }
    val goldText = remember { ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold") }

    Dialog(onDismissRequest = onNextHand) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero: outcome headline gets a centered, bigger treatment so the
            // dialog reads as celebratory rather than utilitarian.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = when {
                        humanWon -> "You win"
                        byFold -> {
                            val winnerName = seats.firstOrNull { it.index in winnerIndices }?.displayName ?: "Player"
                            "$winnerName wins by fold"
                        }
                        else -> "Showdown"
                    },
                    typography = AppTheme.typography.Display.D1200,
                    color = if (humanWon) goldText else AppTheme.colors.onSurfacePrimary,
                )
                if (humanWon) {
                    Text(
                        text = "+$humanWinAmount",
                        typography = AppTheme.typography.Heading.H800,
                        color = goldText,
                    )
                } else {
                    Text(
                        text = "Pot $totalPot",
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.onSurfaceSecondary,
                    )
                }
            }

            // XP earned for this hand. Always shown when something was awarded
            // — even if the player lost the pot — to reinforce the "engagement,
            // not outcome" framing from docs/decisions.md.
            if (xpEarned != null && xpEarned > 0) {
                XpEarnedBubble(amount = xpEarned)
            }

            // Show the community cards so the player can read each hand against
            // the full board.
            if (result.board.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Board",
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.onSurfaceSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        result.board.forEach { card ->
                            PlayingCard(card = card, size = PlayingCardSize.Deck)
                        }
                    }
                }
            }

            // Real-poker behavior: mucked (folded) hands stay hidden. Only
            // players who reached showdown reveal their cards.
            if (!byFold) {
                val rows = seats.filter {
                    it.participation != HandParticipation.NotDealt &&
                        it.participation != HandParticipation.Folded &&
                        it.holeCards.size == 2
                }
                rows.forEach { seat ->
                    ShowdownRow(
                        seat = seat,
                        board = result.board,
                        isWinner = seat.index in winnerIndices,
                        winAmount = result.winners.filter { it.seatIndex == seat.index }.sumOf { it.amount },
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(AppTheme.colors.accentPrimary.color)
                    .clickable(onClick = onNextHand)
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Next hand",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.onAccentPrimary,
                )
            }
        }
    }
}

/**
 * Shown instead of [ShowdownDialog] when the human's stack hit 0 this hand.
 * The auto-rebuy happens silently between hands ([LocalBotsSession]); this
 * modal just makes that moment legible so new players don't wonder how they
 * still have chips next hand. Single "Deal me in" CTA which triggers the
 * normal next-hand advance.
 */
@Composable
internal fun BustDialog(xpEarned: Int?, onDealMeIn: () -> Unit) {
    Dialog(onDismissRequest = onDealMeIn) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(AppTheme.colors.surfaceSecondary.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "💸",
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.text,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "You went bust",
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Text(
                    text = "Practice chips refilled — chips against bots don't count for keeps. Deal yourself in and keep sharpening up.",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (xpEarned != null && xpEarned > 0) {
                XpEarnedBubble(amount = xpEarned)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(32.dp))
                    .background(AppTheme.colors.accentPrimary.color)
                    .clickable(onClick = onDealMeIn)
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Deal me in",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.onAccentPrimary,
                )
            }
        }
    }
}

@Composable
private fun XpEarnedBubble(amount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4FC3F7), Color(0xFF66BB6A)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✦",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
        Text(
            text = "+$amount XP",
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.text,
        )
        Text(
            text = "earned",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun ShowdownRow(
    seat: SeatView,
    board: List<Card>,
    isWinner: Boolean,
    winAmount: Long,
) {
    val category = remember(seat.holeCards, board) {
        if (seat.holeCards.size == 2 && board.size in 3..5) {
            HandEvaluator.evaluate(seat.holeCards + board).category.displayName
        } else null
    }
    val goldText = remember { ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold") }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (seat.isHuman) "You" else seat.displayName,
            typography = AppTheme.typography.Body.B500,
            color = if (isWinner) goldText else AppTheme.colors.text,
            modifier = Modifier.width(64.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            seat.holeCards.forEach { PlayingCard(card = it, size = PlayingCardSize.Deck) }
        }
        Spacer(modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = category ?: "—",
                typography = AppTheme.typography.Body.B400,
                color = if (isWinner) goldText else AppTheme.colors.onSurfaceSecondary,
            )
            if (isWinner && winAmount > 0) {
                Text(
                    text = "+$winAmount",
                    typography = AppTheme.typography.Body.B500,
                    color = goldText,
                )
            }
        }
    }
}

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.cards.EarnedAchievement
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.cosmeticRewardFor
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.gameplay.describe
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryChipBubble
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.LevelProgressGradient
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD100
import com.dangerfield.cards.system.clip
import org.jetbrains.compose.ui.tooling.preview.Preview

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
    earnedAchievements: List<EarnedAchievement>,
    xpMode: XpMode,
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
    val heroEmoji = when {
        humanWon -> "🏆"
        byFold -> "🫳"
        else -> "🃏"
    }

    Dialog(
        onDismissRequest = onNextHand,
        topAccessory = topAccessoryEmoji(emoji = heroEmoji),
    ) {
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

            // MP-mode unlocks stay inline so the game flow isn't interrupted by
            // a follow-up sheet (per the locked split: bots get a focused
            // celebration after dismissal, MP keeps the fast inline row).
            // Bot-mode unlocks render in [AchievementCelebrationSheet] sequenced
            // after this dialog dismisses.
            if (xpMode == XpMode.MULTIPLAYER) {
                earnedAchievements.forEach { earned ->
                    AchievementUnlockedCallout(earned = earned)
                }
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

            VerticalSpacerD100()
            ButtonPrimary(
                onClick = onNextHand,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Next hand")
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
internal fun BustDialog(
    xpEarned: Int?,
    earnedAchievements: List<EarnedAchievement>,
    xpMode: XpMode,
    onDealMeIn: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDealMeIn,
        topAccessory = topAccessoryChipBubble(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
            if (xpMode == XpMode.MULTIPLAYER) {
                earnedAchievements.forEach { earned ->
                    AchievementUnlockedCallout(earned = earned)
                }
            }
            ButtonPrimary(
                onClick = onDealMeIn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Deal me in")
            }
        }
    }
}

/**
 * Per-achievement unlock celebration shown in the hand-end dialogs. Renders
 * as a horizontal card with the achievement's icon + name + reward summary.
 * Multiple achievements stack vertically.
 */
@Composable
private fun AchievementUnlockedCallout(earned: EarnedAchievement) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700)
            .background(PokerPalette.ChipGold.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = earned.achievement.icon,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Achievement unlocked",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
            Text(
                text = earned.achievement.name,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
            if (earned.achievement.description.isNotBlank()) {
                Text(
                    text = earned.achievement.description,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
            val rewardSummary = buildString {
                append("+${earned.achievement.xpReward} XP")
                if (earned.achievement.chipReward > 0L) {
                    append(" · +${formatThousands(earned.achievement.chipReward)} chips")
                }
            }
            Text(
                text = rewardSummary,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
            cosmeticRewardFor(earned.achievement.id)?.let { cosmetic ->
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "🎁",
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.text,
                    )
                    Text(
                        text = "Also unlocked · ${cosmetic.label}",
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.text,
                    )
                }
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
            .clip(Radii.Round)
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(LevelProgressGradient),
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
    // Render the full descriptor ("Pair of Kings, Queen kicker") rather than
    // just the category — that's the difference between "I tied and somehow
    // lost" and "ah, the kicker decided it."
    val handDescription = remember(seat.holeCards, board) {
        if (seat.holeCards.size == 2 && board.size in 3..5) {
            HandEvaluator.evaluate(seat.holeCards + board).describe()
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
                text = handDescription ?: "—",
                typography = AppTheme.typography.Body.B400,
                color = if (isWinner) goldText else AppTheme.colors.onSurfaceSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
            if (isWinner && winAmount > 0) {
                Text(
                    text = "+${formatThousands(winAmount)}",
                    typography = AppTheme.typography.Body.B500,
                    color = goldText,
                )
            }
        }
    }
}

@Preview
@Composable
private fun ShowdownDialogPreview_HumanWinsAtRiver() {
    PreviewContent {
        val board = PreviewSamples.riverBoard()
        ShowdownDialog(
            result = HandResultView(
                winners = listOf(PreviewSamples.handWinner(seatIndex = 0, amount = 240)),
                board = board,
            ),
            seats = listOf(
                PreviewSamples.humanSeat(
                    stack = 1_220,
                    holeCards = listOf(
                        PreviewSamples.card(Rank.Ace, Suit.Spades),
                        PreviewSamples.card(Rank.Ace, Suit.Clubs),
                    ),
                ),
                PreviewSamples.botSeat(
                    index = 1,
                    name = "Jane",
                    stack = 760,
                    holeCards = listOf(
                        PreviewSamples.card(Rank.King, Suit.Hearts),
                        PreviewSamples.card(Rank.Queen, Suit.Hearts),
                    ),
                ),
            ),
            xpEarned = 75,
            earnedAchievements = listOf(
                PreviewSamples.earnedAchievement(
                    name = "Pocket rockets",
                    description = "Win a hand with pocket aces.",
                    icon = "🚀",
                    rarity = AchievementRarity.RARE,
                    xpReward = 200,
                ),
            ),
            xpMode = XpMode.MULTIPLAYER,
            onNextHand = {},
        )
    }
}

@Preview
@Composable
private fun ShowdownDialogPreview_BotWinsByFold() {
    PreviewContent {
        ShowdownDialog(
            result = HandResultView(
                winners = listOf(
                    PreviewSamples.handWinner(seatIndex = 1, amount = 60, byFold = true),
                ),
                board = emptyList(),
            ),
            seats = listOf(
                PreviewSamples.humanSeat(
                    participation = HandParticipation.Folded,
                    lastAction = PlayerAction.Fold,
                ),
                PreviewSamples.botSeat(index = 1, name = "Jane", stack = 1_060),
            ),
            xpEarned = 10,
            earnedAchievements = emptyList(),
            xpMode = XpMode.BOTS,
            onNextHand = {},
        )
    }
}

@Preview
@Composable
private fun BustDialogPreview() {
    PreviewContent {
        BustDialog(
            xpEarned = 25,
            earnedAchievements = emptyList(),
            xpMode = XpMode.BOTS,
            onDealMeIn = {},
        )
    }
}

@Preview
@Composable
private fun BustDialogPreview_WithAchievement() {
    // MP variant — inline row is the legacy shape kept for multiplayer
    // hands. Bot-mode unlocks render in [AchievementCelebrationSheet].
    PreviewContent {
        BustDialog(
            xpEarned = 25,
            earnedAchievements = listOf(
                PreviewSamples.earnedAchievement(
                    name = "Tilt resistance",
                    description = "Bust without leaving the table.",
                    icon = "🧘",
                    rarity = AchievementRarity.COMMON,
                ),
            ),
            xpMode = XpMode.MULTIPLAYER,
            onDealMeIn = {},
        )
    }
}

@Preview
@Composable
private fun ShowdownDialogPreview_AchievementUnlocksCosmetic() {
    PreviewContent {
        ShowdownDialog(
            result = HandResultView(
                winners = listOf(PreviewSamples.handWinner(seatIndex = 0, amount = 4_800)),
                board = PreviewSamples.riverBoard(),
            ),
            seats = listOf(
                PreviewSamples.humanSeat(
                    stack = 5_200,
                    holeCards = listOf(
                        PreviewSamples.card(Rank.Ace, Suit.Spades),
                        PreviewSamples.card(Rank.Ace, Suit.Clubs),
                    ),
                ),
                PreviewSamples.botSeat(
                    index = 1,
                    name = "Mike",
                    stack = 240,
                    holeCards = listOf(
                        PreviewSamples.card(Rank.King, Suit.Hearts),
                        PreviewSamples.card(Rank.King, Suit.Diamonds),
                    ),
                ),
            ),
            xpEarned = 500,
            earnedAchievements = listOf(
                PreviewSamples.earnedAchievement(
                    id = com.dangerfield.cards.libraries.cards.AchievementId.DONT_CALL_IT_COMEBACK,
                    name = "Don't call it a comeback",
                    description = "Dip to 10 BB, climb back to a full 100 BB stack.",
                    icon = "🎙️",
                    rarity = AchievementRarity.EPIC,
                    xpReward = 500,
                ),
            ),
            xpMode = XpMode.MULTIPLAYER,
            onNextHand = {},
        )
    }
}

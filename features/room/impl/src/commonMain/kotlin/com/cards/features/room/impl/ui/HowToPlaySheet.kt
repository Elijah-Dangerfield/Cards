package com.dangerfield.cards.features.room.impl.ui

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_all_in_description
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_all_in_title
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_call_description
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_call_title
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_check_description
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_check_title
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_fold_description
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_fold_title
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_raise_description
import cards.libraries.resources.generated.resources.room_cheat_sheet_action_raise_title
import cards.libraries.resources.generated.resources.room_cheat_sheet_actions_heading
import cards.libraries.resources.generated.resources.room_cheat_sheet_hand_number_pill
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_flush_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_four_of_a_kind_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_full_house_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_high_card_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_pair_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_royal_flush_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_straight_flush_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_straight_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_three_of_a_kind_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_two_pair_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_explainer_complete
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_explainer_flop
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_explainer_preflop
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_explainer_river
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_explainer_showdown
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_explainer_turn
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_label_complete
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_label_flop
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_label_preflop
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_label_river
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_label_showdown
import cards.libraries.resources.generated.resources.room_cheat_sheet_street_label_turn
import cards.libraries.resources.generated.resources.room_cheat_sheet_you_have_label
import cards.libraries.resources.generated.resources.room_how_to_play_full_tutorial_footer
import cards.libraries.resources.generated.resources.room_how_to_play_sheet_title
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandCategory
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD50
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD900
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The "How to play" sheet, opened from the question-mark in the play-screen top
 * bar. Carries the live, situational coaching — what hand you currently hold,
 * what stage the hand is at, and what each action does. The static
 * "what beats what" rankings live on the board-tap [HandRankingsCheatSheet].
 */
@Composable
fun HowToPlaySheet(
    onDismiss: () -> Unit,
    handNumber: Int? = null,
    street: BettingRound? = null,
    pot: Long? = null,
    holeCards: List<Card> = emptyList(),
    boardCards: List<Card> = emptyList(),
) {
    val currentCategory = remember(holeCards, boardCards) {
        currentHandCategory(holeCards, boardCards)
    }
    BottomSheet(
        title = stringResource(Res.string.room_how_to_play_sheet_title),
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.surface,
        dragHandle = topAccessoryEmoji(emoji = "🧭").asDragHandle(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (holeCards.isNotEmpty()) {
                YouHaveBanner(
                    category = currentCategory,
                    holeCards = holeCards,
                    boardCards = boardCards,
                )
                VerticalSpacerD800()
            }

            if (street != null) {
                CurrentHandCard(
                    handNumber = handNumber,
                    street = street,
                    pot = pot,
                )
                VerticalSpacerD900()
            }

            Text(
                text = stringResource(Res.string.room_cheat_sheet_actions_heading),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            VerticalSpacerD600()
            ActionRow(
                symbol = "✓",
                title = stringResource(Res.string.room_cheat_sheet_action_check_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_check_description),
                accent = AppTheme.colors.success,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "=",
                title = stringResource(Res.string.room_cheat_sheet_action_call_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_call_description),
                accent = AppTheme.colors.info,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "↑",
                title = stringResource(Res.string.room_cheat_sheet_action_raise_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_raise_description),
                accent = AppTheme.colors.warning,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "✕",
                title = stringResource(Res.string.room_cheat_sheet_action_fold_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_fold_description),
                accent = AppTheme.colors.danger,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "★",
                title = stringResource(Res.string.room_cheat_sheet_action_all_in_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_all_in_description),
                accent = AppTheme.colors.accentPrimary,
            )

            // Pointer to the full interactive tutorial. Deliberately a plain
            // reminder, not a tappable link: opening Settings mid-game would
            // forfeit the seat, so we tell the player where to find it for later
            // rather than navigating them out now (AUTH-14).
            VerticalSpacerD800()
            Text(
                text = stringResource(Res.string.room_how_to_play_full_tutorial_footer),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun YouHaveBanner(
    category: HandCategory?,
    holeCards: List<Card>,
    boardCards: List<Card>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R1000.shape)
            .background(AppTheme.colors.success.color.copy(alpha = 0.14f))
            .padding(Dimension.D850),
        verticalArrangement = Arrangement.spacedBy(Dimension.D300),
    ) {
        Text(
            text = stringResource(Res.string.room_cheat_sheet_you_have_label),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.contentSecondary,
        )
        category?.let {
            Text(
                text = stringResource(categoryNameResource(it)),
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.success,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                holeCards.forEach { PlayingCard(card = it, size = PlayingCardSize.Mini) }
            }
            if (boardCards.isNotEmpty()) {
                Spacer(modifier = Modifier.width(Dimension.D400))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    boardCards.forEach { PlayingCard(card = it, size = PlayingCardSize.Mini) }
                }
            }
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
            .clip(Radii.R1000.shape)
            .background(AppTheme.colors.surfaceRaised.color)
            .padding(horizontal = Dimension.D850, vertical = Dimension.D850),
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
                text = stringResource(streetLabelResourceFor(street)),
                typography = AppTheme.typography.Heading.H900,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (pot != null && pot > 0) {
                ChipPill(amount = pot)
            }
        }

        Text(
            text = stringResource(streetExplainerResourceFor(street)),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun HandNumberPill(handNumber: Int) {
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfaceHigh.color)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(Res.string.room_cheat_sheet_hand_number_pill, handNumber),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.contentSecondary,
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
                isPast -> AppTheme.colors.contentSecondary.color
                else -> AppTheme.colors.surfaceHigh.color
            }
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(if (isCurrent) Dimension.D850 else 10.dp)
                    .clip(Radii.Round.shape)
                    .background(color),
            )
        }
    }
}

private fun streetExplainerResourceFor(street: BettingRound): StringResource = when (street) {
    BettingRound.Preflop -> Res.string.room_cheat_sheet_street_explainer_preflop
    BettingRound.Flop -> Res.string.room_cheat_sheet_street_explainer_flop
    BettingRound.Turn -> Res.string.room_cheat_sheet_street_explainer_turn
    BettingRound.River -> Res.string.room_cheat_sheet_street_explainer_river
    BettingRound.Showdown -> Res.string.room_cheat_sheet_street_explainer_showdown
    BettingRound.Complete -> Res.string.room_cheat_sheet_street_explainer_complete
}

private fun streetLabelResourceFor(street: BettingRound): StringResource = when (street) {
    BettingRound.Preflop -> Res.string.room_cheat_sheet_street_label_preflop
    BettingRound.Flop -> Res.string.room_cheat_sheet_street_label_flop
    BettingRound.Turn -> Res.string.room_cheat_sheet_street_label_turn
    BettingRound.River -> Res.string.room_cheat_sheet_street_label_river
    BettingRound.Showdown -> Res.string.room_cheat_sheet_street_label_showdown
    BettingRound.Complete -> Res.string.room_cheat_sheet_street_label_complete
}

private fun categoryNameResource(category: HandCategory): StringResource = when (category) {
    HandCategory.RoyalFlush -> Res.string.room_cheat_sheet_ranking_royal_flush_name
    HandCategory.StraightFlush -> Res.string.room_cheat_sheet_ranking_straight_flush_name
    HandCategory.FourOfAKind -> Res.string.room_cheat_sheet_ranking_four_of_a_kind_name
    HandCategory.FullHouse -> Res.string.room_cheat_sheet_ranking_full_house_name
    HandCategory.Flush -> Res.string.room_cheat_sheet_ranking_flush_name
    HandCategory.Straight -> Res.string.room_cheat_sheet_ranking_straight_name
    HandCategory.ThreeOfAKind -> Res.string.room_cheat_sheet_ranking_three_of_a_kind_name
    HandCategory.TwoPair -> Res.string.room_cheat_sheet_ranking_two_pair_name
    HandCategory.Pair -> Res.string.room_cheat_sheet_ranking_pair_name
    HandCategory.HighCard -> Res.string.room_cheat_sheet_ranking_high_card_name
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
            .clip(Radii.R800.shape)
            .background(AppTheme.colors.surfaceRaised.color)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(Radii.Round.shape)
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
                color = AppTheme.colors.content,
            )
            VerticalSpacerD50()
            Text(
                text = desc,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun HowToPlaySheetPreview_MidHand() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        HowToPlaySheet(
            onDismiss = {},
            handNumber = 3,
            street = BettingRound.Flop,
            pot = 240,
            holeCards = listOf(
                Card(com.dangerfield.cards.libraries.gameplay.Rank.Ace, com.dangerfield.cards.libraries.gameplay.Suit.Hearts),
                Card(com.dangerfield.cards.libraries.gameplay.Rank.King, com.dangerfield.cards.libraries.gameplay.Suit.Hearts),
            ),
            boardCards = listOf(
                Card(com.dangerfield.cards.libraries.gameplay.Rank.Queen, com.dangerfield.cards.libraries.gameplay.Suit.Hearts),
                Card(com.dangerfield.cards.libraries.gameplay.Rank.Five, com.dangerfield.cards.libraries.gameplay.Suit.Hearts),
                Card(com.dangerfield.cards.libraries.gameplay.Rank.Two, com.dangerfield.cards.libraries.gameplay.Suit.Hearts),
            ),
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun HowToPlaySheetPreview_NoHand() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        HowToPlaySheet(onDismiss = {})
    }
}

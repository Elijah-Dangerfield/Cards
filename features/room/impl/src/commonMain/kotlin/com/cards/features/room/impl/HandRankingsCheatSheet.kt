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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_flush_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_four_of_a_kind_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_four_of_a_kind_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_full_house_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_full_house_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_high_card_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_high_card_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_pair_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_pair_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_royal_flush_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_royal_flush_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_straight_flush_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_straight_flush_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_straight_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_straight_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_three_of_a_kind_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_three_of_a_kind_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_two_pair_name
import cards.libraries.resources.generated.resources.room_cheat_sheet_ranking_two_pair_tagline
import cards.libraries.resources.generated.resources.room_cheat_sheet_rankings_heading
import cards.libraries.resources.generated.resources.room_cheat_sheet_rankings_subtitle
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
import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BaseBottomSheet
import com.dangerfield.cards.libraries.ui.components.poker.ChipPill
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD1000
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD50
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD900
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class RankingEntry(
    val name: StringResource,
    val tagline: StringResource,
    val cards: List<Card>,
)

private val rankings: List<RankingEntry> = listOf(
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_royal_flush_name,
        tagline = Res.string.room_cheat_sheet_ranking_royal_flush_tagline,
        cards = listOf(
            Card(Rank.Ten, Suit.Spades),
            Card(Rank.Jack, Suit.Spades),
            Card(Rank.Queen, Suit.Spades),
            Card(Rank.King, Suit.Spades),
            Card(Rank.Ace, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_straight_flush_name,
        tagline = Res.string.room_cheat_sheet_ranking_straight_flush_tagline,
        cards = listOf(
            Card(Rank.Five, Suit.Hearts),
            Card(Rank.Six, Suit.Hearts),
            Card(Rank.Seven, Suit.Hearts),
            Card(Rank.Eight, Suit.Hearts),
            Card(Rank.Nine, Suit.Hearts),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_four_of_a_kind_name,
        tagline = Res.string.room_cheat_sheet_ranking_four_of_a_kind_tagline,
        cards = listOf(
            Card(Rank.Jack, Suit.Spades),
            Card(Rank.Jack, Suit.Hearts),
            Card(Rank.Jack, Suit.Diamonds),
            Card(Rank.Jack, Suit.Clubs),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_full_house_name,
        tagline = Res.string.room_cheat_sheet_ranking_full_house_tagline,
        cards = listOf(
            Card(Rank.Queen, Suit.Spades),
            Card(Rank.Queen, Suit.Hearts),
            Card(Rank.Queen, Suit.Diamonds),
            Card(Rank.Five, Suit.Clubs),
            Card(Rank.Five, Suit.Hearts),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_flush_name,
        tagline = Res.string.room_cheat_sheet_ranking_flush_tagline,
        cards = listOf(
            Card(Rank.Ace, Suit.Diamonds),
            Card(Rank.Jack, Suit.Diamonds),
            Card(Rank.Eight, Suit.Diamonds),
            Card(Rank.Five, Suit.Diamonds),
            Card(Rank.Three, Suit.Diamonds),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_straight_name,
        tagline = Res.string.room_cheat_sheet_ranking_straight_tagline,
        cards = listOf(
            Card(Rank.Five, Suit.Spades),
            Card(Rank.Six, Suit.Diamonds),
            Card(Rank.Seven, Suit.Clubs),
            Card(Rank.Eight, Suit.Hearts),
            Card(Rank.Nine, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_three_of_a_kind_name,
        tagline = Res.string.room_cheat_sheet_ranking_three_of_a_kind_tagline,
        cards = listOf(
            Card(Rank.Seven, Suit.Spades),
            Card(Rank.Seven, Suit.Hearts),
            Card(Rank.Seven, Suit.Clubs),
            Card(Rank.King, Suit.Diamonds),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_two_pair_name,
        tagline = Res.string.room_cheat_sheet_ranking_two_pair_tagline,
        cards = listOf(
            Card(Rank.Ace, Suit.Spades),
            Card(Rank.Ace, Suit.Hearts),
            Card(Rank.Eight, Suit.Clubs),
            Card(Rank.Eight, Suit.Diamonds),
            Card(Rank.Three, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_pair_name,
        tagline = Res.string.room_cheat_sheet_ranking_pair_tagline,
        cards = listOf(
            Card(Rank.Ten, Suit.Spades),
            Card(Rank.Ten, Suit.Hearts),
            Card(Rank.Queen, Suit.Clubs),
            Card(Rank.Six, Suit.Diamonds),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
    RankingEntry(
        name = Res.string.room_cheat_sheet_ranking_high_card_name,
        tagline = Res.string.room_cheat_sheet_ranking_high_card_tagline,
        cards = listOf(
            Card(Rank.Ace, Suit.Spades),
            Card(Rank.Jack, Suit.Hearts),
            Card(Rank.Eight, Suit.Clubs),
            Card(Rank.Five, Suit.Diamonds),
            Card(Rank.Two, Suit.Spades),
        ),
    ),
)

@OptIn(LowLevelDSComponent::class)
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
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD600()
            ActionRow(
                symbol = "✓",
                title = stringResource(Res.string.room_cheat_sheet_action_check_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_check_description),
                accent = ColorResource.Green600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "=",
                title = stringResource(Res.string.room_cheat_sheet_action_call_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_call_description),
                accent = ColorResource.Blue600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "↑",
                title = stringResource(Res.string.room_cheat_sheet_action_raise_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_raise_description),
                accent = ColorResource.Orange600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "✕",
                title = stringResource(Res.string.room_cheat_sheet_action_fold_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_fold_description),
                accent = ColorResource.Red600,
            )
            VerticalSpacerD300()
            ActionRow(
                symbol = "★",
                title = stringResource(Res.string.room_cheat_sheet_action_all_in_title),
                desc = stringResource(Res.string.room_cheat_sheet_action_all_in_description),
                accent = ColorResource.Purple600,
            )

            VerticalSpacerD1000()
            Text(
                text = stringResource(Res.string.room_cheat_sheet_rankings_heading),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD200()
            Text(
                text = stringResource(Res.string.room_cheat_sheet_rankings_subtitle),
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
            .clip(Radii.R1000.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
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
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (pot != null && pot > 0) {
                ChipPill(amount = pot)
            }
        }

        Text(
            text = stringResource(streetExplainerResourceFor(street)),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
        )
    }
}

@Composable
private fun HandNumberPill(handNumber: Int) {
    Box(
        modifier = Modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfaceTertiary.color)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = stringResource(Res.string.room_cheat_sheet_hand_number_pill, handNumber),
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
            .background(AppTheme.colors.surfaceSecondary.color)
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
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD50()
            Text(
                text = desc,
                typography = AppTheme.typography.Body.B500,
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
            .clip(Radii.R800.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
                text = stringResource(entry.name),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Text(
                text = stringResource(entry.tagline),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            entry.cards.forEach { PlayingCard(card = it, size = PlayingCardSize.Mini) }
        }
    }
}

private fun streetLabelResourceFor(street: BettingRound): StringResource = when (street) {
    BettingRound.Preflop -> Res.string.room_cheat_sheet_street_label_preflop
    BettingRound.Flop -> Res.string.room_cheat_sheet_street_label_flop
    BettingRound.Turn -> Res.string.room_cheat_sheet_street_label_turn
    BettingRound.River -> Res.string.room_cheat_sheet_street_label_river
    BettingRound.Showdown -> Res.string.room_cheat_sheet_street_label_showdown
    BettingRound.Complete -> Res.string.room_cheat_sheet_street_label_complete
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

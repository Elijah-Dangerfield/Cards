package com.dangerfield.cards.features.room.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.lobby_in_room_code_copied
import cards.libraries.resources.generated.resources.lobby_in_room_code_label
import cards.libraries.resources.generated.resources.lobby_in_room_code_share_hint
import cards.libraries.resources.generated.resources.lobby_in_room_copy_button
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
import com.dangerfield.cards.libraries.gameplay.Card
import com.dangerfield.cards.libraries.gameplay.HandCategory
import com.dangerfield.cards.libraries.gameplay.HandEvaluator
import com.dangerfield.cards.libraries.gameplay.Rank
import com.dangerfield.cards.libraries.gameplay.Suit
import com.dangerfield.cards.libraries.ui.system.LowLevelDSComponent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BaseBottomSheet
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCard
import com.dangerfield.cards.libraries.ui.components.poker.PlayingCardSize
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private data class RankingEntry(
    val category: HandCategory,
    val name: StringResource,
    val tagline: StringResource,
    val cards: List<Card>,
)

private val rankings: List<RankingEntry> = listOf(
    RankingEntry(
        category = HandCategory.RoyalFlush,
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
        category = HandCategory.StraightFlush,
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
        category = HandCategory.FourOfAKind,
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
        category = HandCategory.FullHouse,
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
        category = HandCategory.Flush,
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
        category = HandCategory.Straight,
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
        category = HandCategory.ThreeOfAKind,
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
        category = HandCategory.TwoPair,
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
        category = HandCategory.Pair,
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
        category = HandCategory.HighCard,
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

/**
 * The "what beats what" reference, opened by tapping the board. Static hand
 * rankings (plus the room code, when there is one) — the live, situational
 * coaching (your current hand, the stage, how to act) lives on the top-bar
 * [HowToPlaySheet]. The rankings still highlight the hand you currently hold.
 */
@OptIn(LowLevelDSComponent::class)
@Composable
fun HandRankingsCheatSheet(
    onDismiss: () -> Unit,
    holeCards: List<Card> = emptyList(),
    boardCards: List<Card> = emptyList(),
    roomCode: String? = null,
) {
    val currentCategory = remember(holeCards, boardCards) {
        currentHandCategory(holeCards, boardCards)
    }
    BaseBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheetDragHandle.None,
        backgroundColor = AppTheme.colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            if (roomCode != null) {
                RoomCodeCard(code = roomCode)
                VerticalSpacerD800()
            }

            Text(
                text = stringResource(Res.string.room_cheat_sheet_rankings_heading),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
            )
            VerticalSpacerD600()

            rankings.forEach { entry ->
                RankingCard(entry = entry, isCurrent = entry.category == currentCategory)
                VerticalSpacerD300()
            }
            VerticalSpacerD300()
        }
    }
}

@Composable
private fun RoomCodeCard(code: String) {
    val clipboard = LocalClipboardManager.current
    val copied = stringResource(Res.string.lobby_in_room_code_copied)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R1000.shape)
            .background(AppTheme.colors.accentPrimary.color.copy(alpha = 0.12f))
            .padding(Dimension.D850),
        verticalArrangement = Arrangement.spacedBy(Dimension.D300),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.lobby_in_room_code_label),
            typography = AppTheme.typography.Label.L500,
            color = AppTheme.colors.contentSecondary,
        )
        Text(
            text = code,
            typography = AppTheme.typography.Display.D1000.Italic,
            color = AppTheme.colors.accentPrimary,
        )
        Text(
            text = stringResource(Res.string.lobby_in_room_code_share_hint),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
        )
        VerticalSpacerD300()
        ButtonSecondary(
            onClick = {
                clipboard.setText(AnnotatedString(code))
                showSnackBar(message = copied)
            },
            size = ButtonSize.Small,
            style = ButtonStyle.Outlined,
        ) {
            Text(stringResource(Res.string.lobby_in_room_copy_button))
        }
    }
}

@Composable
private fun RankingCard(entry: RankingEntry, isCurrent: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R800.shape)
            .background(
                if (isCurrent) {
                    AppTheme.colors.success.color.copy(alpha = 0.14f)
                } else {
                    AppTheme.colors.surfaceRaised.color
                },
            )
            .then(
                if (isCurrent) {
                    Modifier.border(2.dp, AppTheme.colors.success.color, Radii.R800.shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Name + a short tagline on the left; the example cards do the heavy
        // lifting on the right, so both sit on one row.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(entry.name),
                typography = AppTheme.typography.Body.B600,
                color = if (isCurrent) AppTheme.colors.success else AppTheme.colors.content,
            )
            Text(
                text = stringResource(entry.tagline),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            entry.cards.forEach { PlayingCard(card = it, size = PlayingCardSize.Mini) }
        }
    }
}

internal fun currentHandCategory(holeCards: List<Card>, boardCards: List<Card>): HandCategory? {
    if (holeCards.size != 2) return null
    val all = holeCards + boardCards
    if (all.size !in 5..7) return null
    if (all.distinct().size != all.size) return null
    return HandEvaluator.evaluate(all).category
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun HandRankingsCheatSheetPreview_MidHand() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        HandRankingsCheatSheet(
            onDismiss = {},
            holeCards = listOf(
                Card(Rank.Ace, Suit.Hearts),
                Card(Rank.King, Suit.Hearts),
            ),
            boardCards = listOf(
                Card(Rank.Queen, Suit.Hearts),
                Card(Rank.Five, Suit.Hearts),
                Card(Rank.Two, Suit.Hearts),
            ),
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
private fun HandRankingsCheatSheetPreview_WithRoomCode() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        HandRankingsCheatSheet(
            onDismiss = {},
            holeCards = listOf(
                Card(Rank.Ace, Suit.Spades),
                Card(Rank.King, Suit.Diamonds),
            ),
            boardCards = listOf(
                Card(Rank.Ace, Suit.Hearts),
                Card(Rank.Seven, Suit.Clubs),
                Card(Rank.Two, Suit.Diamonds),
                Card(Rank.Jack, Suit.Spades),
            ),
            roomCode = "ASTRO",
        )
    }
}

package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_recents_add_friend
import cards.libraries.resources.generated.resources.home_recents_add_friend_sent
import cards.libraries.resources.generated.resources.home_recents_empty_state
import cards.libraries.resources.generated.resources.home_recents_section_title
import cards.libraries.resources.generated.resources.home_recents_see_all
import cards.libraries.resources.generated.resources.home_recents_suggest_friend_game
import cards.libraries.resources.generated.resources.home_recents_suggest_quick_match
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.EdgeToEdgeRow
import com.dangerfield.cards.libraries.ui.components.header.SectionHeader
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * "Recently played with" shelf — the humans the user faced at MP
 * tables, with a one-tap "Add friend" affordance per tile. Solves
 * the cold-start friend-graph problem: a user can opt in to a
 * relationship without having to memorize someone's display name
 * across sessions.
 *
 * Bots are deliberately excluded — you can't friend the house. The
 * filter happens upstream; this composable just renders whatever
 * it's handed.
 *
 * Empty state renders the friend-via-play rule + CTAs into the two
 * flows that can actually surface a future tile here (Friend Game
 * and Quick Match) — this shelf is the only path to friending, so
 * a zero-friend user needs to know how to seed it. Spec's voice
 * rule against "begging" doesn't override the load-bearing
 * explanation here: it's a one-time instruction, not urgency-bait.
 */
@Composable
internal fun RecentlyPlayedWithStrip(
    opponents: List<RecentOpponent>,
    onAddFriend: (RecentOpponent) -> Unit,
    onSeeAll: () -> Unit,
    onStartFriendGame: () -> Unit,
    onStartQuickMatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(Res.string.home_recents_section_title),
            trailingLabel = if (opponents.isEmpty()) null else stringResource(Res.string.home_recents_see_all),
            onClick = if (opponents.isEmpty()) null else onSeeAll,
        )
        VerticalSpacerD500()
        if (opponents.isEmpty()) {
            EmptyRecentOpponents(
                onStartFriendGame = onStartFriendGame,
                onStartQuickMatch = onStartQuickMatch,
            )
        } else {
            EdgeToEdgeRow {
                items(items = opponents, key = { it.id }) { opponent ->
                    OpponentTile(opponent = opponent, onAddFriend = { onAddFriend(opponent) })
                }
            }
        }
    }
}

@Composable
private fun EmptyRecentOpponents(
    onStartFriendGame: () -> Unit,
    onStartQuickMatch: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.home_recents_empty_state),
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
    )
    VerticalSpacerD500()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
    ) {
        SuggestPill(
            label = stringResource(Res.string.home_recents_suggest_friend_game),
            onClick = onStartFriendGame,
            modifier = Modifier.weight(1f),
        )
        SuggestPill(
            label = stringResource(Res.string.home_recents_suggest_quick_match),
            onClick = onStartQuickMatch,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SuggestPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(Radii.R500.shape)
            .background(AppTheme.colors.surface.color)
            .clickable(onClick = onClick)
            .padding(vertical = Dimension.D400, horizontal = Dimension.D500)
            .wrapContentWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.content,
        )
    }
}

@Composable
private fun OpponentTile(opponent: RecentOpponent, onAddFriend: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(TILE_WIDTH)
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D500),
    ) {
        AvatarCircle(
            name = opponent.displayName,
            emoji = opponent.emoji,
            backgroundColorHex = opponent.avatarBackgroundColorHex,
            size = 64.dp,
        )
        VerticalSpacerD200()
        Text(
            text = opponent.displayName,
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        VerticalSpacerD200()
        AddFriendPill(
            sent = opponent.requestSent,
            onClick = onAddFriend,
        )
    }
}

@Composable
private fun AddFriendPill(sent: Boolean, onClick: () -> Unit) {
    val bg = if (sent) AppTheme.colors.surfaceHigh.color
    else AppTheme.colors.accentPrimary.color
    val fg = if (sent) AppTheme.colors.contentSecondary
    else AppTheme.colors.onAccentPrimary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R500.shape)
            .background(bg)
            .clickable(enabled = !sent, onClick = onClick)
            .padding(vertical = Dimension.D300),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(
                if (sent) Res.string.home_recents_add_friend_sent
                else Res.string.home_recents_add_friend,
            ),
            typography = AppTheme.typography.Label.L400,
            color = fg,
        )
    }
}

@Immutable
data class RecentOpponent(
    val id: String,
    val displayName: String,
    val emoji: String?,
    val avatarBackgroundColorHex: String?,
    /** When true, a friend request is already outbound to this user. */
    val requestSent: Boolean = false,
)

private val TILE_WIDTH = 132.dp

// ---------------------------------------------------------------------------
// Previews — a fresh list with one tile already showing "Sent" (the
// idempotent state the friend-graph wire-up flips into), and a single-tile
// state so the see-all link is exercised against a thin scroll.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun RecentlyPlayedWithStripPreview_MixedState() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        RecentlyPlayedWithStrip(
            opponents = listOf(
                RecentOpponent("u1", "Patrice", "🦁", "#C658E4"),
                RecentOpponent("u2", "Jules", "🐙", "#58C0E4", requestSent = true),
                RecentOpponent("u3", "Omar", "🦅", "#A8E458"),
            ),
            onAddFriend = {},
            onSeeAll = {},
            onStartFriendGame = {},
            onStartQuickMatch = {},
        )
    }
}

@Preview
@Composable
private fun RecentlyPlayedWithStripPreview_SingleOpponent() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        RecentlyPlayedWithStrip(
            opponents = listOf(RecentOpponent("u1", "Patrice", "🦁", "#C658E4")),
            onAddFriend = {},
            onSeeAll = {},
            onStartFriendGame = {},
            onStartQuickMatch = {},
        )
    }
}

@Preview
@Composable
private fun RecentlyPlayedWithStripPreview_Empty() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        RecentlyPlayedWithStrip(
            opponents = emptyList(),
            onAddFriend = {},
            onSeeAll = {},
            onStartFriendGame = {},
            onStartQuickMatch = {},
        )
    }
}

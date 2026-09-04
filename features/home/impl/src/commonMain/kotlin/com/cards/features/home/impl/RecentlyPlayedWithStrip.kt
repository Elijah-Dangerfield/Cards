package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import cards.libraries.resources.generated.resources.home_recents_section_title
import cards.libraries.resources.generated.resources.home_recents_see_all
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
 * With no opponents the whole shelf is hidden (early return) rather
 * than rendering an empty header — a zero-friend user just doesn't
 * see it until a real tile exists to populate it.
 */
@Composable
internal fun RecentlyPlayedWithStrip(
    opponents: List<RecentOpponent>,
    onAddFriend: (RecentOpponent) -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (opponents.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(Res.string.home_recents_section_title),
            trailingLabel = stringResource(Res.string.home_recents_see_all),
            onClick = onSeeAll,
        )
        VerticalSpacerD500()
        EdgeToEdgeRow {
            items(items = opponents, key = { it.id }) { opponent ->
                OpponentTile(opponent = opponent, onAddFriend = { onAddFriend(opponent) })
            }
        }
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
// state so the see-all link is exercised against a thin scroll. The empty
// list renders nothing, so there's no empty-state preview.
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
        )
    }
}

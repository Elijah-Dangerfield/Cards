package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * "Friends online" shelf — header (title + see-all) plus a horizontal
 * scroll of friend tiles. Each tile is avatar + display name + a small
 * presence label ("Practice", "Quick match", "Friend room"). Mirrors the
 * recent-achievements / recently-played-with shelves so the three
 * surfaces feel like siblings.
 *
 * Auto-hides when there are no online friends AND no pending requests
 * — per the spec's voice rule, Home doesn't push a "make a friend!"
 * empty state. Pending requests alone still surface so the user sees
 * the inbox indicator at the see-all link.
 */
@Composable
internal fun FriendsStrip(
    friends: List<FriendOnline>,
    pendingRequests: Int,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (friends.isEmpty() && pendingRequests <= 0) return
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = "Friends",
            trailingLabel = seeAllLabel(onlineCount = friends.size, pendingRequests = pendingRequests),
            onClick = onSeeAll,
        )
        VerticalSpacerD500()
        if (friends.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                items(items = friends, key = { it.id }) { friend ->
                    FriendTile(friend = friend, onClick = onSeeAll)
                }
            }
        }
    }
}

@Composable
private fun FriendTile(friend: FriendOnline, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(TILE_WIDTH)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            AvatarCircle(
                name = friend.displayName,
                emoji = friend.emoji,
                backgroundColorHex = friend.avatarBackgroundColorHex,
                size = AVATAR_SIZE,
            )
            // Green presence dot — visual echo of the see-all "N online"
            // count. Bordered so it pops against any avatar background
            // tint without a separate scrim.
            Box(
                modifier = Modifier
                    .size(PRESENCE_DOT_SIZE)
                    .clip(CircleShape)
                    .background(AppTheme.colors.status.okay.color)
                    .border(
                        width = 2.dp,
                        color = AppTheme.colors.background.color,
                        shape = CircleShape,
                    ),
            )
        }
        VerticalSpacerD200()
        Text(
            text = friend.displayName,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = friend.tableLabel,
            typography = AppTheme.typography.Label.L300,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Right-side label on the section header. Prefers the online count when
 * any friends are online, falls back to the pending-requests count
 * otherwise so the strip still earns its space when only the inbox is
 * non-empty.
 */
private fun seeAllLabel(onlineCount: Int, pendingRequests: Int): String = when {
    onlineCount > 0 -> "$onlineCount online · See all"
    pendingRequests == 1 -> "1 request · See all"
    pendingRequests > 1 -> "$pendingRequests requests · See all"
    else -> "See all"
}

/**
 * Lightweight DTO for the friends strip — shaped to drop in once the
 * friends graph lands. `tableLabel` is the short status ("Practice",
 * "Quick match", "Friend room") that renders under the display name.
 */
@Immutable
internal data class FriendOnline(
    val id: String,
    val displayName: String,
    val emoji: String?,
    val avatarBackgroundColorHex: String?,
    val tableLabel: String,
)

private val TILE_WIDTH = 84.dp
private val AVATAR_SIZE = 64.dp
private val PRESENCE_DOT_SIZE = 14.dp

// ---------------------------------------------------------------------------
// Previews — three online friends (matches the screenshot), one friend +
// pending requests so the see-all label flips, empty-with-pending so the
// strip survives on inbox alone.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun FriendsStripPreview_ThreeOnline() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        FriendsStrip(
            friends = listOf(
                FriendOnline("f1", "Jordan", "🦊", "#E48A58", "Friend room"),
                FriendOnline("f2", "Priya", "💀", "#9E9E9E", "Practice"),
                FriendOnline("f3", "Marcus", "🐉", "#5DA75A", "Quick match"),
            ),
            pendingRequests = 0,
            onSeeAll = {},
        )
    }
}

@Preview
@Composable
private fun FriendsStripPreview_OneOnlineWithPending() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        FriendsStrip(
            friends = listOf(FriendOnline("f1", "Jordan", "🦊", "#E48A58", "Friend room")),
            pendingRequests = 2,
            onSeeAll = {},
        )
    }
}

@Preview
@Composable
private fun FriendsStripPreview_EmptyButPending() {
    // Friend graph is empty but the inbox has requests — strip still
    // renders so the user sees the inbox indicator.
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        FriendsStrip(
            friends = emptyList(),
            pendingRequests = 1,
            onSeeAll = {},
        )
    }
}

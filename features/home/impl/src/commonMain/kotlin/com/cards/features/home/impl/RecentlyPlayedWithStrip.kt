package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500

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
 * Returns nothing when [opponents] is empty — Home doesn't push an
 * empty-state pitch (spec voice rule: no begging). Real source
 * wires into the rooms history (todo); fake data for V1.
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSeeAll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Recently played with",
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.text,
            )
            Text(
                text = "See all",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
        VerticalSpacerD500()
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
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
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D500),
    ) {
        AvatarCircle(
            name = opponent.displayName,
            emoji = opponent.emoji,
            backgroundColorHex = opponent.avatarBackgroundColorHex,
            size = 48.dp,
        )
        VerticalSpacerD200()
        Text(
            text = opponent.displayName,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
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
    val bg = if (sent) AppTheme.colors.surfaceTertiary.color
    else AppTheme.colors.accentPrimary.color
    val fg = if (sent) AppTheme.colors.onSurfaceSecondary
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
            text = if (sent) "Sent" else "Add friend",
            typography = AppTheme.typography.Label.L400,
            color = fg,
        )
    }
}

@Immutable
internal data class RecentOpponent(
    val id: String,
    val displayName: String,
    val emoji: String?,
    val avatarBackgroundColorHex: String?,
    /** When true, a friend request is already outbound to this user. */
    val requestSent: Boolean = false,
)

private val TILE_WIDTH = 116.dp

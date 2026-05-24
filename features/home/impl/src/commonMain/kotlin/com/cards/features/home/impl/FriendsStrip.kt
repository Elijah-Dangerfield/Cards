package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.HorizontalSpacerD500
import com.dangerfield.cards.system.Radii

/**
 * "Friends in the Hall" surface per product-spec §2.4 — avatars of
 * the friends currently playing, with a one-line preview of what
 * they're up to. V1 ships this with fake data because the friends
 * graph is V1.x; the surface is shaped so once
 * `FriendsRepository.observeOnline()` exists we can swap [friends]
 * to the real list without touching layout.
 *
 * Renders nothing when [friends] is empty — empty-state copy lives
 * elsewhere (the spec asks for honest "Play a hand with a friend"
 * encouragement, but only after the friends system exists).
 */
@Composable
internal fun FriendsStrip(
    friends: List<FriendOnline>,
    pendingRequests: Int,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (friends.isEmpty() && pendingRequests <= 0) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(onClick = onSeeAll)
            .padding(horizontal = Dimension.D600, vertical = Dimension.D500),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarStack(friends = friends.take(MAX_AVATARS))
        HorizontalSpacerD500()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = friendsHeadline(friends.size, pendingRequests),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
            val preview = friends.firstOrNull()
            if (preview != null) {
                Text(
                    text = "${preview.displayName} · ${preview.tableLabel}",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            } else if (pendingRequests > 0) {
                Text(
                    text = "Tap to review.",
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
        if (pendingRequests > 0) {
            PendingBadge(count = pendingRequests)
            HorizontalSpacerD500()
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = AppTheme.colors.textSecondary.color,
        )
    }
}

@Composable
private fun PendingBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(AppTheme.colors.accentPrimary.color)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D200),
    ) {
        Text(
            text = if (count > 9) "9+" else "$count",
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.onAccentPrimary,
        )
    }
}

private fun friendsHeadline(onlineCount: Int, pendingRequests: Int): String = when {
    onlineCount > 0 -> "$onlineCount ${if (onlineCount == 1) "friend" else "friends"} in the Hall"
    pendingRequests == 1 -> "1 friend request"
    else -> "$pendingRequests friend requests"
}

@Composable
private fun AvatarStack(friends: List<FriendOnline>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        friends.forEachIndexed { index, friend ->
            val offset = if (index == 0) 0.dp else -(STACK_OVERLAP * index)
            Box(
                modifier = Modifier
                    .offset(x = offset)
                    .size(AVATAR_SIZE + 4.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(AppTheme.colors.surfaceSecondary.color),
                contentAlignment = Alignment.Center,
            ) {
                AvatarCircle(
                    name = friend.displayName,
                    emoji = friend.emoji,
                    backgroundColorHex = friend.avatarBackgroundColorHex,
                    size = AVATAR_SIZE,
                )
            }
        }
    }
}

/**
 * Lightweight DTO for the friends strip — shaped to drop in once
 * the friends graph lands. `tableLabel` is the one-line preview
 * ("$50/$100 standard table", "Heads-up vs Bob", etc.).
 */
@Immutable
internal data class FriendOnline(
    val displayName: String,
    val emoji: String?,
    val avatarBackgroundColorHex: String?,
    val tableLabel: String,
)

private val AVATAR_SIZE = 36.dp
private val STACK_OVERLAP = 12.dp
private const val MAX_AVATARS = 3

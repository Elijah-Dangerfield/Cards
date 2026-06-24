package com.dangerfield.cards.features.profile.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_friend_requests_accept
import cards.libraries.resources.generated.resources.profile_friend_requests_count
import cards.libraries.resources.generated.resources.profile_friend_requests_decline
import cards.libraries.resources.generated.resources.profile_friend_requests_empty
import cards.libraries.resources.generated.resources.profile_friend_requests_title
import cards.libraries.resources.generated.resources.profile_friend_requests_wants_to_friend
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.header.SectionHeader
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The inbound friend-requests inbox on the Profile tab. The locked product
 * rule is that the only way to friend someone is the recently-played-with
 * shelf, so the empty state echoes that rule rather than offering a search —
 * the same copy Home's friends strip uses.
 *
 * Each pending request is one row: avatar + name + an Accept (primary) and
 * Decline (ghost) pair. Tapping either fires the callback and the repository
 * drops the row optimistically, so it leaves the inbox immediately.
 */
@Composable
internal fun FriendRequestsSection(
    requests: List<FriendRequestRow>,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(Res.string.profile_friend_requests_title),
            trailingLabel = requests.size
                .takeIf { it > 0 }
                ?.let { stringResource(Res.string.profile_friend_requests_count, it) },
        )
        VerticalSpacerD500()
        if (requests.isEmpty()) {
            Text(
                text = stringResource(Res.string.profile_friend_requests_empty),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Dimension.D500)) {
                requests.forEach { request ->
                    FriendRequestItem(
                        request = request,
                        onAccept = { onAccept(request.id) },
                        onDecline = { onDecline(request.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendRequestItem(
    request: FriendRequestRow,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D400),
    ) {
        AvatarCircle(
            name = request.displayName,
            emoji = request.emoji,
            backgroundColorHex = request.avatarBackgroundColorHex,
            size = 44.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = request.displayName,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.profile_friend_requests_wants_to_friend),
                typography = AppTheme.typography.Label.L300,
                color = AppTheme.colors.contentSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ButtonGhost(onClick = onDecline, size = ButtonSize.Small) {
            Text(stringResource(Res.string.profile_friend_requests_decline))
        }
        ButtonPrimary(onClick = onAccept, size = ButtonSize.Small) {
            Text(stringResource(Res.string.profile_friend_requests_accept))
        }
    }
}

/**
 * Profile-local view of a pending inbound friend request. Kept distinct from
 * the social lib's `FriendProfile` so the screen stays a stateless function of
 * plain inputs (mirrors `OwnedItem`); the EntryPoint maps the repository type
 * to this.
 */
@Immutable
data class FriendRequestRow(
    val id: String,
    val displayName: String,
    val emoji: String?,
    val avatarBackgroundColorHex: String?,
)

@Preview
@Composable
private fun FriendRequestsSectionPreview_WithRequests() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        FriendRequestsSection(
            requests = listOf(
                FriendRequestRow("u1", "Jordan", "🦊", "#E48A58"),
                FriendRequestRow("u2", "Priya", "💀", "#9E9E9E"),
            ),
            onAccept = {},
            onDecline = {},
        )
    }
}

@Preview
@Composable
private fun FriendRequestsSectionPreview_Empty() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        FriendRequestsSection(
            requests = emptyList(),
            onAccept = {},
            onDecline = {},
        )
    }
}

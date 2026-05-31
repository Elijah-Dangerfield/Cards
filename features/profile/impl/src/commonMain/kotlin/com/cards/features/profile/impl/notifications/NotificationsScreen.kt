package com.dangerfield.cards.features.profile.impl.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_notifications_empty_subtitle
import cards.libraries.resources.generated.resources.profile_notifications_empty_title
import cards.libraries.resources.generated.resources.profile_notifications_title
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageKind
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * In-app notifications inbox. Lists all server-scheduled messages of
 * kind [UserMessageKind.Inbox] (acked rows fall out server-side via
 * the nightly sweep; expired rows are filtered locally).
 *
 * Acking model: simply viewing the screen marks every visible row as
 * shown + pending-ack in one DB write. The bottom-bar badge clears
 * immediately because its count query filters on `shown_at IS NULL`.
 * No need to tap individual rows to ack — viewing IS the ack. Mirrors
 * Gmail / Slack "open the inbox = read everything" mental model.
 *
 * Rows with a [UserMessage.deepLink] are tappable; the tap opens the
 * link via the router (passed in from the host). The row stays in the
 * list after the tap until the server-side sweep purges it.
 */
@Composable
fun NotificationsScreen(
    repository: UserMessageRepository,
    onBack: () -> Unit,
    onDeepLinkTap: (url: String) -> Unit,
) {
    val messages by repository.observeInbox().collectAsState(initial = emptyList())

    // Mark everything visible as shown on first composition. Idempotent —
    // re-entering the screen after dismissing won't double-ack since
    // already-shown rows are filtered out by the DAO query.
    LaunchedEffect(Unit) {
        repository.markAllInboxShown()
    }

    NotificationsScreenContent(
        messages = messages,
        onBack = onBack,
        onDeepLinkTap = onDeepLinkTap,
    )
}

@Composable
internal fun NotificationsScreenContent(
    messages: List<UserMessage>,
    onBack: () -> Unit,
    onDeepLinkTap: (url: String) -> Unit,
) {
    val scrollState = rememberScrollState()
    Screen(
        topBar = {
            TopBar(
                title = stringResource(Res.string.profile_notifications_title),
                onNavigateBack = onBack,
                scrollState = scrollState,
            )
        },
    ) { paddingValues ->
        if (messages.isEmpty()) {
            EmptyState(
                modifier = Modifier.screenContentPadding(
                    paddingValues = paddingValues,
                    includeHorizontalInsets = false,
                ),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .screenContentPadding(paddingValues = paddingValues)
                    .padding(vertical = Dimension.D400),
                verticalArrangement = Arrangement.spacedBy(Dimension.D400),
            ) {
                messages.forEach { message ->
                    NotificationCard(
                        message = message,
                        onTap = {
                            val link = message.deepLink
                            if (!link.isNullOrBlank()) onDeepLinkTap(link)
                        },
                    )
                }
                Spacer(Modifier.height(Dimension.D800))
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Dimension.D800),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "🔕",
                typography = AppTheme.typography.Display.D900,
            )
            Spacer(Modifier.height(Dimension.D400))
            Text(
                text = stringResource(Res.string.profile_notifications_empty_title),
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D200))
            Text(
                text = stringResource(Res.string.profile_notifications_empty_subtitle),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NotificationCard(
    message: UserMessage,
    onTap: () -> Unit,
) {
    val isTappable = !message.deepLink.isNullOrBlank()
    val baseModifier = Modifier
        .fillMaxWidth()
        .background(
            color = AppTheme.colors.surfaceRaised.color,
            shape = Radii.Card.shape,
        )
    val rowModifier = if (isTappable) {
        baseModifier.clickable(onClick = onTap)
    } else {
        baseModifier
    }
    Row(
        modifier = rowModifier.padding(Dimension.D600),
        verticalAlignment = Alignment.Top,
    ) {
        message.emoji?.takeUnless { it.isBlank() }?.let { emoji ->
            Box(
                modifier = Modifier
                    .size(Dimension.D1200)
                    .background(
                        color = AppTheme.colors.surfaceHigh.color,
                        shape = Radii.Round.shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = emoji,
                    typography = AppTheme.typography.Heading.H700,
                )
            }
            Spacer(Modifier.width(Dimension.D400))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = message.title,
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.content,
            )
            Spacer(Modifier.height(Dimension.D200))
            Text(
                text = message.body,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}


// ---------- Previews ----------

private fun previewInbox(): List<UserMessage> = listOf(
    UserMessage(
        id = "1",
        kind = UserMessageKind.Inbox,
        emoji = "🎁",
        title = "5,000 chips on us",
        body = "Sorry about the dropped hand earlier — we credited 5,000 chips to your wallet.",
        deepLink = "cards://shop",
        createdAtEpochMs = 0L,
        expiresAtEpochMs = null,
    ),
    UserMessage(
        id = "2",
        kind = UserMessageKind.Inbox,
        emoji = "⚠️",
        title = "Maintenance Sunday",
        body = "We're rolling out a server update Sunday 9pm UTC. Hands in progress will finish; the lobby will be unavailable for ~15 min.",
        deepLink = null,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = null,
    ),
    UserMessage(
        id = "3",
        kind = UserMessageKind.Inbox,
        emoji = null,
        title = "Welcome back",
        body = "Glad you're back. We've kept your chips and progress safe.",
        deepLink = null,
        createdAtEpochMs = 0L,
        expiresAtEpochMs = null,
    ),
)

@Preview
@Composable
private fun NotificationsScreenPreview_WithMessages() {
    PreviewContent {
        NotificationsScreenContent(
            messages = previewInbox(),
            onBack = {},
            onDeepLinkTap = {},
        )
    }
}

@Preview
@Composable
private fun NotificationsScreenPreview_Empty() {
    PreviewContent {
        NotificationsScreenContent(
            messages = emptyList(),
            onBack = {},
            onDeepLinkTap = {},
        )
    }
}

@Preview
@Composable
private fun NotificationsScreenPreview_SingleNoEmojiNoLink() {
    PreviewContent {
        NotificationsScreenContent(
            messages = previewInbox().drop(2).take(1),
            onBack = {},
            onDeepLinkTap = {},
        )
    }
}

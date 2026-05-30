package com.dangerfield.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.cards.InAppMessageManager
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Hosts the [InAppMessageManager]'s current dialog. The manager owns
 * the "one per foreground" gate; this composable owns the rendering.
 *
 * Three branches on dismiss:
 *  - `deepLink` is null → "Got it" tap clears [InAppMessageManager.dismissCurrent].
 *  - `deepLink` is set → "Open" tap fires `Router.openWebLink(deepLink)`
 *    AND dismisses. The dialog row is already pending-ack (marked at
 *    consume time) — the next sync ships it.
 *  - Scrim tap also dismisses.
 */
@Composable
fun UserMessageOverlay(
    manager: InAppMessageManager,
    router: Router,
) {
    val current by manager.current.collectAsState()
    val message = current ?: return

    UserMessageDialogContent(
        message = message,
        onDismiss = { manager.dismissCurrent() },
        onCtaTap = {
            val link = message.deepLink
            if (!link.isNullOrBlank()) router.openWebLink(link)
            manager.dismissCurrent()
        },
    )
}

@Composable
private fun UserMessageDialogContent(
    message: UserMessage,
    onDismiss: () -> Unit,
    onCtaTap: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        topAccessory = message.emoji?.takeUnless { it.isBlank() }?.let { topAccessoryEmoji(emoji = it) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = Dimension.D800,
                    start = Dimension.D800,
                    end = Dimension.D800,
                    bottom = Dimension.D800,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = message.title,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D400))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = message.body,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Dimension.D800))
            ButtonPrimary(
                onClick = onCtaTap,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = message.ctaLabel())
            }
        }
    }
}

private fun UserMessage.ctaLabel(): String =
    if (deepLink.isNullOrBlank()) "Got it" else "Open"

// ---------- Previews ----------

private fun previewMessage(
    title: String = "Welcome back",
    body: String = "We missed you — here's 1,000 chips on the house.",
    emoji: String? = "🎁",
    deepLink: String? = null,
) = UserMessage(
    id = "preview",
    kind = com.dangerfield.cards.libraries.cards.UserMessageKind.Dialog,
    emoji = emoji,
    title = title,
    body = body,
    deepLink = deepLink,
    createdAtEpochMs = 0L,
    expiresAtEpochMs = null,
)

@Preview
@Composable
private fun UserMessageOverlayPreview_WithEmoji_AndDismiss() {
    PreviewContent {
        UserMessageDialogContent(
            message = previewMessage(),
            onDismiss = {},
            onCtaTap = {},
        )
    }
}

@Preview
@Composable
private fun UserMessageOverlayPreview_WithDeepLink() {
    PreviewContent {
        UserMessageDialogContent(
            message = previewMessage(
                title = "Maintenance Sunday",
                body = "We're rolling out a server update at 9pm UTC. Hands in progress will finish; the lobby will be unavailable for ~15 min.",
                emoji = "⚠️",
                deepLink = "cards://help",
            ),
            onDismiss = {},
            onCtaTap = {},
        )
    }
}

@Preview
@Composable
private fun UserMessageOverlayPreview_NoEmoji() {
    PreviewContent {
        UserMessageDialogContent(
            message = previewMessage(
                title = "Heads up",
                body = "We adjusted your chip balance. Your latest hand wasn't counted due to a server glitch — chips have been refunded.",
                emoji = null,
            ),
            onDismiss = {},
            onCtaTap = {},
        )
    }
}

@Preview
@Composable
private fun UserMessageOverlayPreview_LongBody() {
    PreviewContent {
        UserMessageDialogContent(
            message = previewMessage(
                title = "What's new",
                body = (1..20).joinToString("\n\n") {
                    "Paragraph $it — long body content to confirm the dialog scrolls without overflow when content is taller than the screen."
                },
                emoji = "✨",
            ),
            onDismiss = {},
            onCtaTap = {},
        )
    }
}

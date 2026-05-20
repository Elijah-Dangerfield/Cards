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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.cards.UserMessage
import com.dangerfield.cards.libraries.cards.UserMessageRepository
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import kotlinx.coroutines.launch

/**
 * Renders the head of [UserMessageRepository.unread] as a one-shot dialog.
 * After dismiss / CTA tap, the repository acks the head and the next
 * unread message (if any) takes its place on the next composition.
 *
 * Sits at the app root inside [AppThemeProvider] so it has access to
 * [LocalDialogHostState] from the same composition the rest of the
 * dialogs use — the message just shows up over whatever screen the
 * user is on.
 *
 * Visibility model: only one message is shown at a time. We deliberately
 * don't queue / stack — the user dismisses one, the next pops on next
 * frame. A burst of admin grants stays digestible.
 */
@Composable
fun UserMessageOverlay(
    repository: UserMessageRepository,
    router: Router,
) {
    val unread by repository.unread.collectAsState()
    val head = unread.firstOrNull() ?: return
    val scope = rememberCoroutineScope()

    val ack: () -> Unit = {
        scope.launch { repository.ack(head.id) }
    }
    val ackAndFollow: () -> Unit = {
        val link = head.deepLink
        if (!link.isNullOrBlank()) router.openWebLink(link)
        scope.launch { repository.ack(head.id) }
    }

    Dialog(
        onDismissRequest = ack,
        emoji = head.emoji?.takeUnless { it.isBlank() }?.let { DialogEmoji(emoji = it) },
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
                text = head.title,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D400))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = head.body,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(Dimension.D800))
            ButtonPrimary(
                onClick = if (head.deepLink.isNullOrBlank()) ack else ackAndFollow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = head.ctaLabel())
            }
        }
    }
}

private fun UserMessage.ctaLabel(): String =
    if (deepLink.isNullOrBlank()) "Got it" else "Open"

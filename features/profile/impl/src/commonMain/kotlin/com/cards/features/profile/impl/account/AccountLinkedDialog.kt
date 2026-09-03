package com.dangerfield.cards.features.profile.impl.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.account_linked_dialog_body
import cards.libraries.resources.generated.resources.account_linked_dialog_primary_cta
import cards.libraries.resources.generated.resources.account_linked_dialog_reassurance
import cards.libraries.resources.generated.resources.account_linked_dialog_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

/**
 * Confirmation shown once an anonymous guest links a real identity
 * (Google/Apple) from the claim-account screen. The link is instant, so
 * without this the screen just popped back to Profile with no signal that
 * anything happened. Reassures the user their existing guest progress is now
 * saved and names the provider they linked. Distinct from the sign-up welcome
 * (starter grant) and the plain sign-in bounce.
 */
@Composable
internal fun AccountLinkedDialog(
    providerLabel: String,
    onDismiss: () -> Unit,
    state: DialogState = rememberDialogState(),
) {
    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "🎉"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimension.D800),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = stringResource(Res.string.account_linked_dialog_title),
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D600))
            Text(
                text = stringResource(Res.string.account_linked_dialog_body, providerLabel),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D400))
            Text(
                text = stringResource(Res.string.account_linked_dialog_reassurance, providerLabel),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimension.D1000))
            ButtonPrimary(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.account_linked_dialog_primary_cta))
            }
        }
    }
}

@Preview
@Composable
private fun AccountLinkedDialogPreview_Google() {
    PreviewContent {
        AccountLinkedDialog(providerLabel = "Google", onDismiss = {})
    }
}

@Preview
@Composable
private fun AccountLinkedDialogPreview_Apple() {
    PreviewContent {
        AccountLinkedDialog(providerLabel = "Apple", onDismiss = {})
    }
}

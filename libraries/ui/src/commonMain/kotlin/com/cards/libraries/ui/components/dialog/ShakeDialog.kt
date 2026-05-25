package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ShakeDialog(
    onDismiss: () -> Unit,
    onSendFeedback: () -> Unit,
    modifier: Modifier = Modifier,
    state: DialogState = rememberDialogState(),
) {
    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        modifier = modifier,
        topContent = {
            Text(
                text = "Send feedback?",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Text(
                text = "Shake any time to share a bug or idea.",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        bottomContent = {
            Column {
                Button(
                    onClick = {
                        state.dismiss()
                        onSendFeedback()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Medium,
                    type = ButtonType.Primary,
                ) {
                    Text("Send")
                }
                Spacer(modifier = Modifier.height(Dimension.D500))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Medium,
                    style = ButtonStyle.Text,
                ) {
                    Text("Cancel")
                }
            }
        },
    )
}

@Preview
@Composable
private fun ShakeDialogPreview() {
    PreviewContent {
        ShakeDialog(
            onDismiss = {},
            onSendFeedback = {},
        )
    }
}

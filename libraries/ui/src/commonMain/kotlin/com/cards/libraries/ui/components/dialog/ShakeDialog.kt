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
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun ShakeDialog(
    headline: String,
    subtext: String?,
    onDismiss: () -> Unit,
    onReportBug: () -> Unit,
    modifier: Modifier = Modifier,
    state: DialogState = rememberDialogState(),
) {
    Dialog(
        state = state,
        onDismissRequest = onDismiss,
        modifier = modifier,
        topContent = {
            Text(
                text = headline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (subtext != null) {
                    Spacer(modifier = Modifier.height(Dimension.D300))
                    Text(
                        text = subtext,
                        typography = AppTheme.typography.Body.B600,
                        color = AppTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VerticalSpacerD500()
                }
            }
        },
        bottomContent = {
            Column{
                Button(
                    onClick = {
                        state.dismiss()
                        onReportBug()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Medium,
                    type = ButtonType.Danger,
                ) {
                    Text("Report a bug")
                }
                
                Spacer(modifier = Modifier.height(Dimension.D500))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Medium,
                    style = ButtonStyle.Text
                ) {
                    Text("Dismiss")
                }
            }
        }
    )
}

@Preview
@Composable
private fun ShakeDialogPreview_WithSubtext() {
    PreviewContent {
        ShakeDialog(
            headline = "I felt that.",
            subtext = "Testing the waters?",
            onDismiss = {},
            onReportBug = {},
        )
    }
}

@Preview
@Composable
private fun ShakeDialogPreview_NoSubtext() {
    PreviewContent {
        ShakeDialog(
            headline = "Whoa.",
            subtext = null,
            onDismiss = {},
            onReportBug = {},
        )
    }
}

@Preview
@Composable
private fun ShakeDialogPreview_LongMessage() {
    PreviewContent {
        ShakeDialog(
            headline = "You really like shaking me.",
            subtext = "I've lost count.",
            onDismiss = {},
            onReportBug = {},
        )
    }
}

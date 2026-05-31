package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.ui_shake_dialog_body
import cards.libraries.resources.generated.resources.ui_shake_dialog_cancel_cta
import cards.libraries.resources.generated.resources.ui_shake_dialog_send_cta
import cards.libraries.resources.generated.resources.ui_shake_dialog_title
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.resources.stringResource
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
                text = stringResource(Res.string.ui_shake_dialog_title),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Text(
                text = stringResource(Res.string.ui_shake_dialog_body),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.contentSecondary,
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
                    Text(stringResource(Res.string.ui_shake_dialog_send_cta))
                }
                Spacer(modifier = Modifier.height(Dimension.D500))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    size = ButtonSize.Medium,
                    style = ButtonStyle.Text,
                ) {
                    Text(stringResource(Res.string.ui_shake_dialog_cancel_cta))
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

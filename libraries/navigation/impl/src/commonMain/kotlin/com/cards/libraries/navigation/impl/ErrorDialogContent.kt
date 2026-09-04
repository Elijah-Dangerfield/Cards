package com.dangerfield.cards.libraries.navigation.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogState
import com.dangerfield.cards.libraries.ui.components.dialog.rememberDialogState
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD1000
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun ErrorDialog(
    state: DialogState,
    title: String,
    subtitle: String,
    actionTitle: String,
    errorCode: Int?,
    onDismissRequest: () -> Unit,
    onAction: () -> Unit,
    onReportToDeveloper: (() -> Unit)? = null,
    reportActionTitle: String = "Report to developers",
) {
    val showReportButton = onReportToDeveloper != null

    Dialog(
        state = state,
        onDismissRequest = onDismissRequest,
        topContent = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimension.D200)) {
                Text(text = title)
            }
        },
        content = {
            Column {
                Text(text = subtitle)

                errorCode?.let {
                    VerticalSpacerD1000()
                    Text(
                        text = "Error code: $it",
                        typography = AppTheme.typography.Body.B500.Bold,
                        color = AppTheme.colors.contentSecondary,
                    )
                }

            }
        },
        bottomContent = {
            Column {
                Button(
                    size = ButtonSize.Medium,
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = actionTitle)
                }
                if (showReportButton) {
                    Spacer(modifier = Modifier.height(Dimension.D600))
                    Button(
                        size = ButtonSize.Medium,
                        type = ButtonType.Secondary,
                        onClick = { onReportToDeveloper() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = reportActionTitle)
                    }
                }
            }
        }
    )
}

@Preview
@Composable
private fun ErrorDialogContentPreview() {
    PreviewContent {
        ErrorDialog(
            state = rememberDialogState(),
            title = "Yikes.",
            subtitle = "We hit a snag while loading. Please try again in a sec.",
            actionTitle = "Okay",
            errorCode = 1003,
            onDismissRequest = {},
            onAction = {},
            onReportToDeveloper = {}
        )
    }
}

package com.dangerfield.cards.features.home.impl.bugreport

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD1000
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.SectionCard
import com.dangerfield.cards.libraries.ui.components.SummaryRow
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val BUG_REPORT_CHAR_LIMIT = 180

@Composable
fun BugReportScreen(
    state: BugReportState,
    onAction: (BugReportAction) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val canSubmit = state.message.isNotBlank() && !state.isSubmitting

    Screen(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopBar(
                title = "Report a Bug",
                onNavigateBack = { onAction(BugReportAction.Back) }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .screenContentPadding(
                    paddingValues = paddingValues,
                    includeImePadding = true
                ),
            verticalArrangement = Arrangement.Top
        ) {
            VerticalSpacerD1000()

            if (state.hasContext) {
                SectionCard(title = "Captured details") {
                    state.contextMessage?.let {
                        Text(
                            text = it,
                            typography = AppTheme.typography.Body.B500,
                            color = AppTheme.colors.danger
                        )
                    }

                    state.errorCode?.let {
                        SummaryRow(
                            label = "Error code",
                            value = "$it"
                        )
                    }

                    state.logId?.let {
                        SummaryRow(
                            label = "Report id",
                            value = it
                        )
                    }
                }

                VerticalSpacerD1000()
            }

            Text(
                text = "Help us understand what went wrong. We would love to fix it!",
                typography = AppTheme.typography.Body.B700,
                color = AppTheme.colors.textSecondary
            )

            VerticalSpacerD1000()

            OutlinedTextField(
                value = state.message,
                onValueChange = { newValue ->
                    val limited = newValue.take(BUG_REPORT_CHAR_LIMIT)
                    onAction(BugReportAction.MessageChanged(limited))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimension.D1900),
                label = { Text("Message") },
                placeholder = { Text("Describe what happened…") },
                singleLine = false,
                minLines = 6,
                maxLines = 10,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSubmit) {
                            focusManager.clearFocus(force = true)
                            onAction(BugReportAction.Submit)
                        }
                    }
                )
            )

            VerticalSpacerD500()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val messageLength = state.message.length
                val counterColor = if (messageLength >= BUG_REPORT_CHAR_LIMIT) {
                    AppTheme.colors.danger
                } else {
                    AppTheme.colors.textSecondary
                }

                Text(
                    text = "$messageLength/$BUG_REPORT_CHAR_LIMIT",
                    color = counterColor,
                    typography = AppTheme.typography.Body.B500
                )
            }

            state.errorMessage?.let {
                VerticalSpacerD500()
                Text(
                    text = it,
                    color = AppTheme.colors.danger,
                    textAlign = TextAlign.Start
                )
            }

            VerticalSpacerD1000()

            Button(
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Large,
                enabled = canSubmit,
                onClick = {
                    focusManager.clearFocus(force = true)
                    if (canSubmit) {
                        onAction(BugReportAction.Submit)
                    }
                }
            ) {
                Text(if (state.isSubmitting) "Sending…" else "Send")
            }

            VerticalSpacerD500()
        }
    }
}

@Preview
@Composable
private fun BugReportScreenPreview() {
    PreviewContent {
        BugReportScreen(
            state = BugReportState(
                message = "The shortcut sheet would not open.",
                logId = "12356j32345k1",
                errorCode = 1200,
                contextMessage = "Something went wrong while loading."
            ),
            onAction = {}
        )
    }
}

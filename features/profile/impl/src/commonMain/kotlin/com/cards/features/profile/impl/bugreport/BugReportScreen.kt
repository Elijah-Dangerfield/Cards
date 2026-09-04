package com.dangerfield.cards.features.profile.impl.bugreport

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
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_bug_char_counter
import cards.libraries.resources.generated.resources.profile_bug_context_error_code_label
import cards.libraries.resources.generated.resources.profile_bug_context_report_id_label
import cards.libraries.resources.generated.resources.profile_bug_context_section_title
import cards.libraries.resources.generated.resources.profile_bug_error_message_required
import cards.libraries.resources.generated.resources.profile_bug_error_submit_failed
import cards.libraries.resources.generated.resources.profile_bug_field_email_label
import cards.libraries.resources.generated.resources.profile_bug_field_email_placeholder
import cards.libraries.resources.generated.resources.profile_bug_field_message_label
import cards.libraries.resources.generated.resources.profile_bug_field_message_placeholder
import cards.libraries.resources.generated.resources.profile_bug_hero
import cards.libraries.resources.generated.resources.profile_bug_submit_button
import cards.libraries.resources.generated.resources.profile_bug_submit_button_progress
import cards.libraries.resources.generated.resources.profile_bug_title
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD1000
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.ScreenshotAttachmentField
import com.dangerfield.cards.libraries.ui.components.SectionCard
import com.dangerfield.cards.libraries.ui.components.SummaryRow
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val BUG_REPORT_CHAR_LIMIT = 180
private const val EMAIL_CHAR_LIMIT = 254

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
                title = stringResource(Res.string.profile_bug_title),
                onNavigateBack = { onAction(BugReportAction.Back) },
                scrollState = scrollState,
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
                SectionCard(title = stringResource(Res.string.profile_bug_context_section_title)) {
                    state.contextMessage?.let {
                        Text(
                            text = it,
                            typography = AppTheme.typography.Body.B500,
                            color = AppTheme.colors.danger
                        )
                    }

                    state.errorCode?.let {
                        SummaryRow(
                            label = stringResource(Res.string.profile_bug_context_error_code_label),
                            value = "$it"
                        )
                    }

                    state.logId?.let {
                        SummaryRow(
                            label = stringResource(Res.string.profile_bug_context_report_id_label),
                            value = it
                        )
                    }
                }

                VerticalSpacerD1000()
            }

            Text(
                text = stringResource(Res.string.profile_bug_hero),
                typography = AppTheme.typography.Body.B700,
                color = AppTheme.colors.contentSecondary
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
                label = { Text(stringResource(Res.string.profile_bug_field_message_label)) },
                placeholder = { Text(stringResource(Res.string.profile_bug_field_message_placeholder)) },
                singleLine = false,
                minLines = 6,
                maxLines = 10,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
            )

            VerticalSpacerD1000()

            OutlinedTextField(
                value = state.email,
                onValueChange = { newValue ->
                    onAction(BugReportAction.EmailChanged(newValue.take(EMAIL_CHAR_LIMIT)))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(Res.string.profile_bug_field_email_label)) },
                placeholder = { Text(stringResource(Res.string.profile_bug_field_email_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSubmit) {
                            focusManager.clearFocus(force = true)
                            onAction(BugReportAction.Submit)
                        }
                    }
                )
            )

            VerticalSpacerD1000()

            ScreenshotAttachmentField(
                screenshots = state.screenshots,
                onPicked = { onAction(BugReportAction.ScreenshotsPicked(it)) },
                onRemove = { onAction(BugReportAction.ScreenshotRemoved(it)) },
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
                    AppTheme.colors.contentSecondary
                }

                Text(
                    text = stringResource(
                        Res.string.profile_bug_char_counter,
                        messageLength,
                        BUG_REPORT_CHAR_LIMIT,
                    ),
                    color = counterColor,
                    typography = AppTheme.typography.Body.B500
                )
            }

            state.errorMessage?.let { err ->
                VerticalSpacerD500()
                Text(
                    text = err.message(),
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
                Text(
                    if (state.isSubmitting) {
                        stringResource(Res.string.profile_bug_submit_button_progress)
                    } else {
                        stringResource(Res.string.profile_bug_submit_button)
                    }
                )
            }

            VerticalSpacerD500()
        }
    }
}

@Preview
@Composable
private fun BugReportScreenPreview_Empty() {
    PreviewContent {
        BugReportScreen(
            state = BugReportState(),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun BugReportScreenPreview_WithCapturedContext() {
    PreviewContent {
        BugReportScreen(
            state = BugReportState(
                message = "The shortcut sheet would not open.",
                logId = "12356j32345k1",
                errorCode = 1200,
                contextMessage = "Something went wrong while loading.",
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun BugReportScreenPreview_Submitting() {
    PreviewContent {
        BugReportScreen(
            state = BugReportState(
                message = "App froze when I opened the shop.",
                email = "me@example.com",
                isSubmitting = true,
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun BugReportScreenPreview_Error() {
    PreviewContent {
        BugReportScreen(
            state = BugReportState(
                message = "",
                errorMessage = BugReportError.MessageRequired,
            ),
            onAction = {},
        )
    }
}

@Preview
@Composable
private fun BugReportScreenPreview_SubmitFailed() {
    PreviewContent {
        BugReportScreen(
            state = BugReportState(
                message = "App froze when I opened the shop.",
                errorMessage = BugReportError.SubmitFailed,
            ),
            onAction = {},
        )
    }
}

@Composable
private fun BugReportError.message(): String = when (this) {
    BugReportError.MessageRequired ->
        stringResource(Res.string.profile_bug_error_message_required)
    BugReportError.SubmitFailed ->
        stringResource(Res.string.profile_bug_error_submit_failed)
}

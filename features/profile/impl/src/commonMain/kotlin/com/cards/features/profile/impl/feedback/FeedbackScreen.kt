package com.dangerfield.cards.features.profile.impl.feedback

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
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import org.jetbrains.compose.ui.tooling.preview.Preview

private const val FEEDBACK_CHAR_LIMIT = 200
private const val EMAIL_CHAR_LIMIT = 254

@Composable
fun FeedbackScreen(
    state: FeedbackState,
    onAction: (FeedbackAction) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    val canSubmit = state.message.isNotBlank() && !state.isSubmitting

    Screen(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopBar(
                 title = "Share Your Feedback",
                onNavigateBack = { onAction(FeedbackAction.Back) },
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

            Text(
                text = "We'd love to hear from you",
                typography = AppTheme.typography.Body.B700,
                color = AppTheme.colors.textSecondary
            )

            VerticalSpacerD500()

            OutlinedTextField(
                value = state.message,
                onValueChange = { newValue ->
                    val limited = newValue.take(FEEDBACK_CHAR_LIMIT)
                    onAction(FeedbackAction.MessageChanged(limited))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(Dimension.D1900),
                label = { Text("Message") },
                placeholder = { Text("Describe what happened…") },
                singleLine = false,
                minLines = 6,
                maxLines = 10,
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Next),
            )

            VerticalSpacerD500()

            OutlinedTextField(
                value = state.email,
                onValueChange = { newValue ->
                    onAction(FeedbackAction.EmailChanged(newValue.take(EMAIL_CHAR_LIMIT)))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Email (so we can follow up)") },
                placeholder = { Text("optional") },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default.copy(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSubmit) {
                            focusManager.clearFocus(force = true)
                            onAction(FeedbackAction.Submit)
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
                val counterColor = if (messageLength >= FEEDBACK_CHAR_LIMIT) {
                    AppTheme.colors.danger
                } else {
                    AppTheme.colors.textSecondary
                }

                Text(
                    text = "$messageLength/$FEEDBACK_CHAR_LIMIT",
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
                        onAction(FeedbackAction.Submit)
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
private fun FeedbackScreenPreviewDisabled() {
    PreviewContent {
        FeedbackScreen(
            state = FeedbackState(
                message = ""
            ),
            onAction = {}
        )
    }
}

@Preview
@Composable
private fun FeedbackScreenPreview() {
    PreviewContent {
        FeedbackScreen(
            state = FeedbackState(
                message = "I love this app!"
            ),
            onAction = {}
        )
    }
}

package com.dangerfield.cards.features.profile.impl.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_delete_back_a11y
import cards.libraries.resources.generated.resources.profile_delete_bullet_history
import cards.libraries.resources.generated.resources.profile_delete_bullet_identity
import cards.libraries.resources.generated.resources.profile_delete_bullet_oauth
import cards.libraries.resources.generated.resources.profile_delete_bullet_progress
import cards.libraries.resources.generated.resources.profile_delete_cancel_button
import cards.libraries.resources.generated.resources.profile_delete_confirm_input_label
import cards.libraries.resources.generated.resources.profile_delete_error_network
import cards.libraries.resources.generated.resources.profile_delete_error_not_configured
import cards.libraries.resources.generated.resources.profile_delete_error_unknown
import cards.libraries.resources.generated.resources.profile_delete_section_title
import cards.libraries.resources.generated.resources.profile_delete_submit_button
import cards.libraries.resources.generated.resources.profile_delete_submit_button_progress
import cards.libraries.resources.generated.resources.profile_delete_subtitle
import cards.libraries.resources.generated.resources.profile_delete_title
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.components.button.ButtonDanger
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource

/**
 * Type-to-confirm destructive screen. Lists exactly what gets deleted so
 * there's no ambiguity post-tap, gates the delete button on the user
 * typing [DeleteAccountState.REQUIRED_PHRASE] (case-insensitive), and
 * routes any failure into the same body of copy so we don't switch to a
 * full-screen error state mid-flow.
 *
 * No "delete in N seconds" countdown by design — fewer chances to fat-
 * finger an undo flow that doesn't exist.
 */
@Composable
fun DeleteAccountScreen(
    state: DeleteAccountState,
    onAction: (DeleteAccountAction) -> Unit,
    onBack: () -> Unit,
) {
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .screenContentPadding(paddingValues = padding, includeImePadding = true),
            verticalArrangement = Arrangement.Top,
        ) {
                Spacer(modifier = Modifier.height(Dimension.D200))
                IconButton(
                    icon = Icons.ArrowBack(stringResource(Res.string.profile_delete_back_a11y)),
                    onClick = onBack,
                    enabled = !state.isSubmitting,
                    iconColor = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D700))

                // Emoji-as-hero so the destructive screen reads as a
                // real warning at a glance, before the user even parses
                // the title.
                Text(
                    text = "⚠️",
                    typography = AppTheme.typography.Display.D800,
                )
                Spacer(modifier = Modifier.height(Dimension.D500))

                Text(
                    text = stringResource(Res.string.profile_delete_title),
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                Text(
                    text = stringResource(Res.string.profile_delete_subtitle),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )

                Spacer(modifier = Modifier.height(Dimension.D700))

                Text(
                    text = stringResource(Res.string.profile_delete_section_title),
                    typography = AppTheme.typography.Heading.H500,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                BulletItem(stringResource(Res.string.profile_delete_bullet_identity))
                BulletItem(stringResource(Res.string.profile_delete_bullet_progress))
                BulletItem(stringResource(Res.string.profile_delete_bullet_history))
                BulletItem(stringResource(Res.string.profile_delete_bullet_oauth))

                Spacer(modifier = Modifier.height(Dimension.D700))

                OutlinedTextField(
                    value = state.confirmationInput,
                    onValueChange = { onAction(DeleteAccountAction.ConfirmationChanged(it)) },
                    enabled = !state.isSubmitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(DeleteAccountAction.ConfirmDelete) }),
                    label = {
                        Text(
                            stringResource(
                                Res.string.profile_delete_confirm_input_label,
                                DeleteAccountState.REQUIRED_PHRASE,
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let { err ->
                    Spacer(modifier = Modifier.height(Dimension.D400))
                    Text(
                        text = err.message(),
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.danger,
                    )
                }

                Spacer(modifier = Modifier.height(Dimension.D800))

                ButtonDanger(
                    onClick = { onAction(DeleteAccountAction.ConfirmDelete) },
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (state.isSubmitting) {
                            stringResource(Res.string.profile_delete_submit_button_progress)
                        } else {
                            stringResource(Res.string.profile_delete_submit_button)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(Dimension.D400))

                Button(
                    onClick = onBack,
                    enabled = !state.isSubmitting,
                    style = ButtonStyle.Text,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.profile_delete_cancel_button))
                }

            Spacer(modifier = Modifier.height(Dimension.D800))
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun DeleteAccountScreenPreview_Empty() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        DeleteAccountScreen(state = DeleteAccountState(), onAction = {}, onBack = {})
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun DeleteAccountScreenPreview_Confirmed() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        DeleteAccountScreen(
            state = DeleteAccountState(confirmationInput = "delete"),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun DeleteAccountScreenPreview_Error() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        DeleteAccountScreen(
            state = DeleteAccountState(
                confirmationInput = "delete",
                error = DeleteAccountError.NetworkError,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun DeleteAccountScreenPreview_Submitting() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        DeleteAccountScreen(
            state = DeleteAccountState(
                confirmationInput = "delete",
                isSubmitting = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Composable
private fun BulletItem(text: String) {
    Text(
        text = "• $text",
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.contentSecondary,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun DeleteAccountError.message(): String = when (this) {
    DeleteAccountError.NotConfigured ->
        stringResource(Res.string.profile_delete_error_not_configured)
    DeleteAccountError.NetworkError ->
        stringResource(Res.string.profile_delete_error_network)
    DeleteAccountError.Unknown ->
        stringResource(Res.string.profile_delete_error_unknown)
}

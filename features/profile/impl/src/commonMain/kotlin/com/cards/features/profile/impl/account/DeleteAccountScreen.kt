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
                    icon = Icons.ArrowBack("Back"),
                    onClick = onBack,
                    enabled = !state.isSubmitting,
                    iconColor = AppTheme.colors.onSurfacePrimary,
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
                    text = "Delete account",
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                Text(
                    text = "This permanently deletes your account from our servers. " +
                        "There is no undo.",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                )

                Spacer(modifier = Modifier.height(Dimension.D700))

                Text(
                    text = "What gets deleted",
                    typography = AppTheme.typography.Heading.H500,
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                BulletItem("Your display name and avatar.")
                BulletItem("Your chips, XP, level, rank, and unlocked achievements.")
                BulletItem("Your hand history.")
                BulletItem("Any linked Apple/Google identity on this account.")

                Spacer(modifier = Modifier.height(Dimension.D700))

                OutlinedTextField(
                    value = state.confirmationInput,
                    onValueChange = { onAction(DeleteAccountAction.ConfirmationChanged(it)) },
                    enabled = !state.isSubmitting,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(DeleteAccountAction.ConfirmDelete) }),
                    label = { Text("Type \"${DeleteAccountState.REQUIRED_PHRASE}\" to confirm") },
                    modifier = Modifier.fillMaxWidth(),
                )

                state.error?.let {
                    Spacer(modifier = Modifier.height(Dimension.D400))
                    Text(
                        text = it,
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
                    Text(if (state.isSubmitting) "Deleting…" else "Delete my account")
                }

                Spacer(modifier = Modifier.height(Dimension.D400))

                Button(
                    onClick = onBack,
                    enabled = !state.isSubmitting,
                    style = ButtonStyle.Text,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Cancel")
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
                error = "Couldn't reach the server. Check your connection.",
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
        color = AppTheme.colors.onSurfaceSecondary,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

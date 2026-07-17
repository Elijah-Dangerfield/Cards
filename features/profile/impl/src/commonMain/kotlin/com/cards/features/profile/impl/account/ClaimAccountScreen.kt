package com.dangerfield.cards.features.profile.impl.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.auth_claim_error_already_on_another_account
import cards.libraries.resources.generated.resources.auth_claim_error_email_already_registered
import cards.libraries.resources.generated.resources.auth_claim_error_invalid_email
import cards.libraries.resources.generated.resources.auth_claim_error_network
import cards.libraries.resources.generated.resources.auth_claim_error_not_signed_in
import cards.libraries.resources.generated.resources.auth_claim_error_provider_not_enabled
import cards.libraries.resources.generated.resources.auth_claim_error_switch_failed
import cards.libraries.resources.generated.resources.auth_claim_error_timeout
import cards.libraries.resources.generated.resources.auth_claim_error_unknown
import cards.libraries.resources.generated.resources.auth_claim_error_weak_password
import cards.libraries.resources.generated.resources.auth_field_email_label
import cards.libraries.resources.generated.resources.auth_field_password_label
import cards.libraries.resources.generated.resources.auth_sign_in_oauth_divider
import cards.libraries.resources.generated.resources.auth_sign_up_confirm_password_label
import cards.libraries.resources.generated.resources.auth_sign_up_password_helper
import cards.libraries.resources.generated.resources.auth_sign_up_password_mismatch_helper
import cards.libraries.resources.generated.resources.auth_sign_up_submit_button
import cards.libraries.resources.generated.resources.auth_sign_up_submit_button_progress
import cards.libraries.resources.generated.resources.profile_claim_back_a11y
import cards.libraries.resources.generated.resources.profile_claim_conflict_cancel_button
import cards.libraries.resources.generated.resources.profile_claim_conflict_switch_button
import cards.libraries.resources.generated.resources.profile_claim_conflict_title
import cards.libraries.resources.generated.resources.profile_claim_provider_apple
import cards.libraries.resources.generated.resources.profile_claim_provider_google
import cards.libraries.resources.generated.resources.profile_claim_subtitle
import cards.libraries.resources.generated.resources.profile_claim_title
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AppleSignInButton
import com.dangerfield.cards.libraries.ui.components.AppleSignInButtonKind
import com.dangerfield.cards.libraries.ui.components.AppleSignInButtonStyle
import com.dangerfield.cards.libraries.ui.components.GoogleSignInButton
import com.dangerfield.cards.libraries.ui.components.GoogleSignInButtonTheme
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.PasswordTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Combined claim-account screen — email/password form at the top, OAuth
 * (Google/Apple) buttons below an "or" divider. Replaces the earlier
 * three-button menu + separate email screen with a single canvas.
 *
 * Provider gating still hides OAuth buttons until their Supabase
 * dashboard credentials exist (the `identity.*` config flags); the email form
 * is the always-available path. The conflicting-OAuth-identity state
 * shows the same destructive-style confirm button as before — per the
 * 2026-05-18 Identity pivot decision we don't auto-merge anonymous
 * progress.
 */
@Composable
fun ClaimAccountScreen(
    state: ClaimAccountState,
    onAction: (ClaimAccountAction) -> Unit,
    onBack: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val submit = {
        keyboardController?.hide()
        onAction(ClaimAccountAction.Submit)
    }
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .screenContentPadding(
                    paddingValues = padding,
                    includeImePadding = true,
                ),
            verticalArrangement = Arrangement.Top,
        ) {
                Spacer(modifier = Modifier.height(Dimension.D200))
                IconButton(
                    icon = Icons.ArrowBack(stringResource(Res.string.profile_claim_back_a11y)),
                    onClick = onBack,
                    enabled = !state.isSubmitting,
                    iconColor = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D700))

                Text(
                    text = "🔐",
                    typography = AppTheme.typography.Display.D800,
                )
                Spacer(modifier = Modifier.height(Dimension.D500))

                Text(
                    text = stringResource(Res.string.profile_claim_title),
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.content,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                Text(
                    text = stringResource(Res.string.profile_claim_subtitle),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )

                Spacer(modifier = Modifier.height(Dimension.D900))

                EmailField(
                    value = state.email,
                    enabled = !state.isSubmitting,
                    onChange = { onAction(ClaimAccountAction.EmailChanged(it)) },
                )
                Spacer(modifier = Modifier.height(Dimension.D500))
                PasswordTextField(
                    value = state.password,
                    onValueChange = { onAction(ClaimAccountAction.PasswordChanged(it)) },
                    label = stringResource(Res.string.auth_field_password_label),
                    enabled = !state.isSubmitting,
                    imeAction = ImeAction.Next,
                    onImeAction = submit,
                    helper = stringResource(
                        Res.string.auth_sign_up_password_helper,
                        ClaimAccountState.MIN_PASSWORD_LENGTH,
                    ),
                )
                Spacer(modifier = Modifier.height(Dimension.D500))
                PasswordTextField(
                    value = state.confirmPassword,
                    onValueChange = { onAction(ClaimAccountAction.ConfirmPasswordChanged(it)) },
                    label = stringResource(Res.string.auth_sign_up_confirm_password_label),
                    enabled = !state.isSubmitting,
                    imeAction = ImeAction.Go,
                    onImeAction = submit,
                    helper = if (state.passwordMismatch) {
                        stringResource(Res.string.auth_sign_up_password_mismatch_helper)
                    } else {
                        null
                    },
                    isError = state.passwordMismatch,
                )

                // Inline errors (network, unknown, etc.). The "account already
                // exists" conflict is surfaced as an explicit confirm dialog
                // instead (see ClaimConflictDialog), so suppress it here.
                state.error?.takeIf { state.conflictingProvider == null }?.let {
                    Spacer(modifier = Modifier.height(Dimension.D400))
                    Text(
                        text = it.message(),
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.danger,
                    )
                }

                Spacer(modifier = Modifier.height(Dimension.D600))

                Button(
                    onClick = submit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (state.isSubmitting) Res.string.auth_sign_up_submit_button_progress
                            else Res.string.auth_sign_up_submit_button,
                        ),
                    )
                }

                if (state.anyProviderEnabled) {
                    Spacer(modifier = Modifier.height(Dimension.D700))
                    Text(
                        text = stringResource(Res.string.auth_sign_in_oauth_divider),
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.contentSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(Dimension.D500))
                    if (state.googleEnabled) {
                        GoogleSignInButton(
                            text = stringResource(Res.string.profile_claim_provider_google),
                            enabled = !state.isSubmitting,
                            isLoading = state.isSubmitting,
                            theme = GoogleSignInButtonTheme.Dark,
                            onClick = {
                                onAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(Dimension.D400))
                    }
                    if (state.appleEnabled) {
                        // Native ASAuthorizationAppleIDButton (iOS-only slot) —
                        // runs the system sheet via the coordinator, not the web flow.
                        AppleSignInButton(
                            onClick = { onAction(ClaimAccountAction.ClaimWithApple) },
                            enabled = !state.isSubmitting,
                            isLoading = state.isSubmitting,
                            kind = AppleSignInButtonKind.ContinueFlow,
                            // White per Apple HIG for dark backgrounds (our canvas is near-black).
                            style = AppleSignInButtonStyle.Light,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

            Spacer(modifier = Modifier.height(Dimension.D800))
        }

        // An existing account is already tied to this provider — make switching
        // (which abandons guest progress) an explicit, dismissible choice rather
        // than an inline "continue anyway" button.
        state.conflictingProvider?.let { provider ->
            ClaimConflictDialog(
                provider = provider,
                isSubmitting = state.isSubmitting,
                onSwitch = { onAction(ClaimAccountAction.ConfirmSwitchToExisting) },
                onCancel = { onAction(ClaimAccountAction.DismissError) },
            )
        }
    }
}

@Composable
private fun ClaimConflictDialog(
    provider: OAuthProvider,
    isSubmitting: Boolean,
    onSwitch: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(
        title = stringResource(Res.string.profile_claim_conflict_title),
        onDismissRequest = onCancel,
        topAccessory = topAccessoryEmoji(emoji = "👤"),
    ) {
        Text(stringResource(Res.string.auth_claim_error_already_on_another_account))
        // Stacked, full-width buttons — the "Switch to my <provider> account"
        // label is too long to share a row without truncating.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimension.D300),
        ) {
            ButtonPrimary(
                onClick = onSwitch,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.profile_claim_conflict_switch_button, provider.label)) }
            ButtonSecondary(
                onClick = onCancel,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.profile_claim_conflict_cancel_button)) }
        }
    }
}

@Composable
private fun EmailField(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(),
        label = { Text(stringResource(Res.string.auth_field_email_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ClaimAccountError.message(): String = when (this) {
    ClaimAccountError.AlreadyOnAnotherAccount ->
        stringResource(Res.string.auth_claim_error_already_on_another_account)
    ClaimAccountError.NotSignedIn ->
        stringResource(Res.string.auth_claim_error_not_signed_in)
    is ClaimAccountError.ProviderNotEnabled ->
        stringResource(Res.string.auth_claim_error_provider_not_enabled, provider.label)
    ClaimAccountError.NetworkError ->
        stringResource(Res.string.auth_claim_error_network)
    ClaimAccountError.Timeout ->
        stringResource(Res.string.auth_claim_error_timeout)
    ClaimAccountError.Unknown ->
        stringResource(Res.string.auth_claim_error_unknown)
    ClaimAccountError.SwitchFailed ->
        stringResource(Res.string.auth_claim_error_switch_failed)
    ClaimAccountError.EmailAlreadyRegistered ->
        stringResource(Res.string.auth_claim_error_email_already_registered)
    is ClaimAccountError.WeakPassword ->
        stringResource(Res.string.auth_claim_error_weak_password, minLength)
    ClaimAccountError.InvalidEmail ->
        stringResource(Res.string.auth_claim_error_invalid_email)
}

@Preview
@Composable
private fun ClaimAccountScreenPreview_EmailOnly() {
    PreviewContent {
        ClaimAccountScreen(state = ClaimAccountState(), onAction = {}, onBack = {})
    }
}

@Preview
@Composable
private fun ClaimAccountScreenPreview_BothProvidersEnabled() {
    PreviewContent {
        ClaimAccountScreen(
            state = ClaimAccountState(googleEnabled = true, appleEnabled = true),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ClaimAccountScreenPreview_Filled() {
    PreviewContent {
        ClaimAccountScreen(
            state = ClaimAccountState(
                googleEnabled = true,
                appleEnabled = true,
                email = "you@example.com",
                password = "hunter22ish",
                confirmPassword = "hunter22ish",
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ClaimAccountScreenPreview_PasswordMismatch() {
    PreviewContent {
        ClaimAccountScreen(
            state = ClaimAccountState(
                googleEnabled = true,
                appleEnabled = true,
                email = "you@example.com",
                password = "hunter22ish",
                confirmPassword = "hunter22is",
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ClaimAccountScreenPreview_Submitting() {
    PreviewContent {
        ClaimAccountScreen(
            state = ClaimAccountState(
                googleEnabled = true,
                appleEnabled = true,
                email = "you@example.com",
                password = "hunter22ish",
                confirmPassword = "hunter22ish",
                isSubmitting = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ClaimAccountScreenPreview_Conflict() {
    PreviewContent {
        ClaimAccountScreen(
            state = ClaimAccountState(
                googleEnabled = true,
                appleEnabled = true,
                conflictingProvider = OAuthProvider.Google,
                error = ClaimAccountError.AlreadyOnAnotherAccount,
            ),
            onAction = {},
            onBack = {},
        )
    }
}


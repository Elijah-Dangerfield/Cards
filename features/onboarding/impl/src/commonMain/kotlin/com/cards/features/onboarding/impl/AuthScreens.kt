package com.dangerfield.cards.features.onboarding.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.auth_back_button
import cards.libraries.resources.generated.resources.auth_field_email_label
import cards.libraries.resources.generated.resources.auth_field_password_label
import cards.libraries.resources.generated.resources.auth_forgot_password_back_to_sign_in_button
import cards.libraries.resources.generated.resources.auth_forgot_password_banner_generic_error
import cards.libraries.resources.generated.resources.auth_forgot_password_banner_network_error
import cards.libraries.resources.generated.resources.auth_forgot_password_banner_rate_limited
import cards.libraries.resources.generated.resources.auth_forgot_password_link
import cards.libraries.resources.generated.resources.auth_forgot_password_sent_body
import cards.libraries.resources.generated.resources.auth_forgot_password_sent_title
import cards.libraries.resources.generated.resources.auth_forgot_password_submit_button
import cards.libraries.resources.generated.resources.auth_forgot_password_submit_button_progress
import cards.libraries.resources.generated.resources.auth_forgot_password_subtitle
import cards.libraries.resources.generated.resources.auth_forgot_password_title
import cards.libraries.resources.generated.resources.auth_sign_in_error_invalid_credentials
import cards.libraries.resources.generated.resources.auth_sign_in_error_network
import cards.libraries.resources.generated.resources.auth_sign_in_error_provider_not_enabled
import cards.libraries.resources.generated.resources.auth_sign_in_error_unknown
import cards.libraries.resources.generated.resources.auth_sign_in_oauth_apple
import cards.libraries.resources.generated.resources.auth_sign_in_oauth_divider
import cards.libraries.resources.generated.resources.auth_sign_in_oauth_google
import cards.libraries.resources.generated.resources.auth_sign_in_submit_button
import cards.libraries.resources.generated.resources.auth_sign_in_submit_button_progress
import cards.libraries.resources.generated.resources.auth_sign_in_subtitle
import cards.libraries.resources.generated.resources.auth_sign_in_title
import cards.libraries.resources.generated.resources.auth_sign_in_to_create_account_button
import cards.libraries.resources.generated.resources.auth_sign_up_confirm_password_label
import cards.libraries.resources.generated.resources.auth_sign_up_error_email_already_registered
import cards.libraries.resources.generated.resources.auth_sign_up_error_invalid_email
import cards.libraries.resources.generated.resources.auth_sign_up_error_network
import cards.libraries.resources.generated.resources.auth_sign_up_error_timeout
import cards.libraries.resources.generated.resources.auth_sign_up_error_unknown
import cards.libraries.resources.generated.resources.auth_sign_up_error_weak_password
import cards.libraries.resources.generated.resources.auth_sign_up_password_helper
import cards.libraries.resources.generated.resources.auth_sign_up_password_mismatch_helper
import cards.libraries.resources.generated.resources.auth_sign_up_submit_button
import cards.libraries.resources.generated.resources.auth_sign_up_submit_button_progress
import cards.libraries.resources.generated.resources.auth_sign_up_subtitle
import cards.libraries.resources.generated.resources.auth_sign_up_title
import cards.libraries.resources.generated.resources.auth_sign_up_to_sign_in_button
import cards.libraries.resources.generated.resources.auth_verify_email_banner_generic_error
import cards.libraries.resources.generated.resources.auth_verify_email_banner_network_error
import cards.libraries.resources.generated.resources.auth_verify_email_banner_resend_rate_limited
import cards.libraries.resources.generated.resources.auth_verify_email_banner_resend_sent
import cards.libraries.resources.generated.resources.auth_verify_email_banner_still_pending
import cards.libraries.resources.generated.resources.auth_verify_email_body
import cards.libraries.resources.generated.resources.auth_verify_email_body_no_email
import cards.libraries.resources.generated.resources.auth_verify_email_confirm_button
import cards.libraries.resources.generated.resources.auth_verify_email_confirm_button_progress
import cards.libraries.resources.generated.resources.auth_verify_email_resend_button
import cards.libraries.resources.generated.resources.auth_verify_email_resend_button_progress
import cards.libraries.resources.generated.resources.auth_verify_email_title
import com.dangerfield.cards.libraries.identity.auth.OAuthProvider
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.components.AppleSignInButton
import com.dangerfield.cards.libraries.ui.components.AppleSignInButtonKind
import com.dangerfield.cards.libraries.ui.components.AppleSignInButtonStyle
import com.dangerfield.cards.libraries.ui.components.GoogleSignInButton
import com.dangerfield.cards.libraries.ui.components.GoogleSignInButtonTheme
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.PasswordTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Shared shell for the three email/password screens. Same vertical layout
 * everywhere: scrollable content, sticky bottom CTA stack, IME padding so
 * the keyboard doesn't cover the focused field.
 *
 * The intentional repetition between SignIn / SignUp screens (email +
 * password + a single big CTA) doesn't justify a deeper abstraction —
 * each screen's strings, validation, and footer link are different, and
 * extracting an `AuthFormScreen` would mostly move conditionals to a
 * config object.
 */
@Composable
private fun AuthShell(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimension.D800),
            ) {
                Spacer(modifier = Modifier.height(Dimension.D200))
                Button(
                    onClick = onBack,
                    style = ButtonStyle.Text,
                    icon = Icons.ChevronLeft(null),
                ) {
                    Text(stringResource(Res.string.auth_back_button))
                }
                Spacer(modifier = Modifier.height(Dimension.D700))
                content()
                Spacer(modifier = Modifier.height(Dimension.D800))
            }
        }
    }
}

@Composable
fun SignInScreen(
    state: SignInState,
    onAction: (SignInAction) -> Unit,
    onBack: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val submit = {
        keyboardController?.hide()
        onAction(SignInAction.Submit)
    }
    AuthShell(onBack = onBack) {
        Text(
            text = stringResource(Res.string.auth_sign_in_title),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = stringResource(Res.string.auth_sign_in_subtitle),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )

        if (state.anyOAuthEnabled) {
            Spacer(modifier = Modifier.height(Dimension.D800))
            if (state.googleEnabled) {
                GoogleSignInButton(
                    text = stringResource(Res.string.auth_sign_in_oauth_google),
                    onClick = { onAction(SignInAction.SignInWithOAuth(OAuthProvider.Google)) },
                    enabled = !state.isSubmitting,
                    isLoading = state.isSubmitting,
                    theme = GoogleSignInButtonTheme.Dark,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(Dimension.D400))
            }
            if (state.appleEnabled) {
                // Native ASAuthorizationAppleIDButton (iOS-only slot) — runs the
                // system sheet via the coordinator, not the web flow.
                AppleSignInButton(
                    onClick = { onAction(SignInAction.SignInWithApple) },
                    enabled = !state.isSubmitting,
                    isLoading = state.isSubmitting,
                    kind = AppleSignInButtonKind.SignIn,
                    // White per Apple HIG for dark backgrounds (our canvas is near-black).
                    style = AppleSignInButtonStyle.Light,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.height(Dimension.D700))
            Text(
                text = stringResource(Res.string.auth_sign_in_oauth_divider),
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(Dimension.D900))

        EmailField(
            value = state.email,
            enabled = !state.isSubmitting,
            onChange = { onAction(SignInAction.EmailChanged(it)) },
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
        PasswordTextField(
            value = state.password,
            onValueChange = { onAction(SignInAction.PasswordChanged(it)) },
            label = stringResource(Res.string.auth_field_password_label),
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Go,
            onImeAction = submit,
        )

        state.error?.let {
            Spacer(modifier = Modifier.height(Dimension.D400))
            ErrorText(it.message())
        }

        Spacer(modifier = Modifier.height(Dimension.D300))

        Button(
            onClick = onForgotPassword,
            style = ButtonStyle.Text,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.auth_forgot_password_link))
        }

        Spacer(modifier = Modifier.height(Dimension.D500))

        Button(
            onClick = submit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.isSubmitting) Res.string.auth_sign_in_submit_button_progress
                    else Res.string.auth_sign_in_submit_button,
                ),
            )
        }

        Spacer(modifier = Modifier.height(Dimension.D400))

        Button(
            onClick = onCreateAccount,
            style = ButtonStyle.Text,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.auth_sign_in_to_create_account_button))
        }
    }
}

@Composable
fun ForgotPasswordScreen(
    state: ForgotPasswordState,
    onAction: (ForgotPasswordAction) -> Unit,
    onBack: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val submit = {
        keyboardController?.hide()
        onAction(ForgotPasswordAction.Submit)
    }
    AuthShell(onBack = onBack) {
        Text(
            text = stringResource(
                if (state.sent) Res.string.auth_forgot_password_sent_title
                else Res.string.auth_forgot_password_title,
            ),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = if (state.sent) {
                stringResource(Res.string.auth_forgot_password_sent_body, state.email.trim())
            } else {
                stringResource(Res.string.auth_forgot_password_subtitle)
            },
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )

        if (!state.sent) {
            Spacer(modifier = Modifier.height(Dimension.D900))
            EmailField(
                value = state.email,
                enabled = !state.isSubmitting,
                onChange = { onAction(ForgotPasswordAction.EmailChanged(it)) },
                imeAction = ImeAction.Go,
                onSubmitImeAction = submit,
            )
        }

        state.banner?.let { banner ->
            Spacer(modifier = Modifier.height(Dimension.D500))
            ForgotPasswordBannerText(banner)
        }

        Spacer(modifier = Modifier.height(Dimension.D800))

        if (state.sent) {
            Button(
                onClick = onBackToSignIn,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.auth_forgot_password_back_to_sign_in_button))
            }
        } else {
            Button(
                onClick = submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (state.isSubmitting) Res.string.auth_forgot_password_submit_button_progress
                        else Res.string.auth_forgot_password_submit_button,
                    ),
                )
            }
        }
    }
}

@Composable
fun SignUpScreen(
    state: SignUpState,
    onAction: (SignUpAction) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val submit = {
        keyboardController?.hide()
        onAction(SignUpAction.Submit)
    }
    AuthShell(onBack = onBack) {
        Text(
            text = stringResource(Res.string.auth_sign_up_title),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = stringResource(Res.string.auth_sign_up_subtitle),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )

        Spacer(modifier = Modifier.height(Dimension.D900))

        EmailField(
            value = state.email,
            enabled = !state.isSubmitting,
            onChange = { onAction(SignUpAction.EmailChanged(it)) },
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
        PasswordTextField(
            value = state.password,
            onValueChange = { onAction(SignUpAction.PasswordChanged(it)) },
            label = stringResource(Res.string.auth_field_password_label),
            enabled = !state.isSubmitting,
            imeAction = ImeAction.Next,
            onImeAction = submit,
            helper = stringResource(
                Res.string.auth_sign_up_password_helper,
                SignUpState.MIN_PASSWORD_LENGTH,
            ),
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
        PasswordTextField(
            value = state.confirmPassword,
            onValueChange = { onAction(SignUpAction.ConfirmPasswordChanged(it)) },
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

        state.error?.let {
            Spacer(modifier = Modifier.height(Dimension.D400))
            ErrorText(it.message())
        }

        Spacer(modifier = Modifier.height(Dimension.D800))

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

        Spacer(modifier = Modifier.height(Dimension.D400))

        Button(
            onClick = onSignIn,
            style = ButtonStyle.Text,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.auth_sign_up_to_sign_in_button))
        }
    }
}

@Composable
fun VerifyEmailScreen(
    state: VerifyEmailState,
    onAction: (VerifyEmailAction) -> Unit,
    onBack: () -> Unit,
) {
    LifecycleResumeEffect(Unit) {
        onAction(VerifyEmailAction.AppResumed)
        onPauseOrDispose { }
    }
    AuthShell(onBack = onBack) {
        Text(
            text = "📧",
            typography = AppTheme.typography.Display.D900,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D600))

        Text(
            text = stringResource(Res.string.auth_verify_email_title),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D400))

        Text(
            text = if (state.email.isEmpty()) {
                stringResource(Res.string.auth_verify_email_body_no_email)
            } else {
                stringResource(Res.string.auth_verify_email_body, state.email)
            },
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Dimension.D1000))

        state.banner?.let { banner ->
            BannerText(banner)
            Spacer(modifier = Modifier.height(Dimension.D400))
        }

        Button(
            onClick = { onAction(VerifyEmailAction.IClickedTheLink) },
            enabled = !state.isRefreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.isRefreshing) Res.string.auth_verify_email_confirm_button_progress
                    else Res.string.auth_verify_email_confirm_button,
                ),
            )
        }

        Spacer(modifier = Modifier.height(Dimension.D400))

        Button(
            onClick = { onAction(VerifyEmailAction.Resend) },
            style = ButtonStyle.Text,
            enabled = !state.isResending && !state.isRefreshing && state.email.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (state.isResending) Res.string.auth_verify_email_resend_button_progress
                    else Res.string.auth_verify_email_resend_button,
                ),
            )
        }
    }
}

// ---- Field helpers ----

@Composable
private fun EmailField(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    imeAction: ImeAction = ImeAction.Next,
    onSubmitImeAction: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Email,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onGo = { onSubmitImeAction?.invoke() },
            onNext = { onSubmitImeAction?.invoke() },
        ),
        label = { Text(stringResource(Res.string.auth_field_email_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.danger,
    )
}

@Composable
private fun SignInError.message(): String = when (this) {
    SignInError.InvalidCredentials -> stringResource(Res.string.auth_sign_in_error_invalid_credentials)
    SignInError.NetworkError -> stringResource(Res.string.auth_sign_in_error_network)
    SignInError.ProviderNotEnabled -> stringResource(Res.string.auth_sign_in_error_provider_not_enabled)
    SignInError.Unknown -> stringResource(Res.string.auth_sign_in_error_unknown)
}

@Composable
private fun SignUpError.message(): String = when (this) {
    SignUpError.EmailAlreadyRegistered ->
        stringResource(Res.string.auth_sign_up_error_email_already_registered)
    is SignUpError.WeakPassword ->
        stringResource(Res.string.auth_sign_up_error_weak_password, minLength)
    SignUpError.InvalidEmail ->
        stringResource(Res.string.auth_sign_up_error_invalid_email)
    SignUpError.NetworkError ->
        stringResource(Res.string.auth_sign_up_error_network)
    SignUpError.Timeout ->
        stringResource(Res.string.auth_sign_up_error_timeout)
    SignUpError.Unknown ->
        stringResource(Res.string.auth_sign_up_error_unknown)
}

@Composable
private fun BannerText(banner: VerifyEmailState.Banner) {
    val (resource, color) = bannerResource(banner)
    Text(
        text = stringResource(resource),
        typography = AppTheme.typography.Body.B500,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ForgotPasswordBannerText(banner: ForgotPasswordState.Banner) {
    val (resource, color) = forgotPasswordBannerResource(banner)
    Text(
        text = stringResource(resource),
        typography = AppTheme.typography.Body.B500,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
@Suppress("DEPRECATION")
private fun forgotPasswordBannerResource(
    banner: ForgotPasswordState.Banner,
): Pair<StringResource, ColorResource> = when (banner) {
    ForgotPasswordState.Banner.RateLimited ->
        Res.string.auth_forgot_password_banner_rate_limited to AppTheme.colors.danger
    ForgotPasswordState.Banner.NetworkError ->
        Res.string.auth_forgot_password_banner_network_error to AppTheme.colors.danger
    ForgotPasswordState.Banner.GenericError ->
        Res.string.auth_forgot_password_banner_generic_error to AppTheme.colors.danger
}

@Composable
@Suppress("DEPRECATION")
private fun bannerResource(banner: VerifyEmailState.Banner): Pair<StringResource, ColorResource> =
    when (banner) {
        VerifyEmailState.Banner.StillPending ->
            Res.string.auth_verify_email_banner_still_pending to AppTheme.colors.contentSecondary
        VerifyEmailState.Banner.ResendSent ->
            Res.string.auth_verify_email_banner_resend_sent to AppTheme.colors.contentSecondary
        VerifyEmailState.Banner.ResendRateLimited ->
            Res.string.auth_verify_email_banner_resend_rate_limited to AppTheme.colors.danger
        VerifyEmailState.Banner.NetworkError ->
            Res.string.auth_verify_email_banner_network_error to AppTheme.colors.danger
        VerifyEmailState.Banner.GenericError ->
            Res.string.auth_verify_email_banner_generic_error to AppTheme.colors.danger
    }

@Preview
@Composable
private fun SignInScreenPreview_EmailOnly() {
    PreviewContent {
        SignInScreen(
            state = SignInState(email = "elijah@example.com", password = "••••••••"),
            onAction = {},
            onBack = {},
            onCreateAccount = {},
            onForgotPassword = {},
        )
    }
}

@Preview
@Composable
private fun SignInScreenPreview_AllProvidersEnabled() {
    PreviewContent {
        SignInScreen(
            state = SignInState(googleEnabled = true, appleEnabled = true),
            onAction = {},
            onBack = {},
            onCreateAccount = {},
            onForgotPassword = {},
        )
    }
}

@Preview
@Composable
private fun SignInScreenPreview_Error() {
    PreviewContent {
        SignInScreen(
            state = SignInState(
                email = "elijah@example.com",
                password = "wrongpass",
                error = SignInError.InvalidCredentials,
            ),
            onAction = {},
            onBack = {},
            onCreateAccount = {},
            onForgotPassword = {},
        )
    }
}

@Preview
@Composable
private fun SignUpScreenPreview_Filled() {
    PreviewContent {
        SignUpScreen(
            state = SignUpState(
                email = "elijah@example.com",
                password = "hunter22ish",
                confirmPassword = "hunter22ish",
            ),
            onAction = {},
            onBack = {},
            onSignIn = {},
        )
    }
}

@Preview
@Composable
private fun SignUpScreenPreview_PasswordMismatch() {
    PreviewContent {
        SignUpScreen(
            state = SignUpState(
                email = "elijah@example.com",
                password = "hunter22ish",
                confirmPassword = "hunter22is",
            ),
            onAction = {},
            onBack = {},
            onSignIn = {},
        )
    }
}

@Preview
@Composable
private fun SignUpScreenPreview_Submitting() {
    PreviewContent {
        SignUpScreen(
            state = SignUpState(
                email = "elijah@example.com",
                password = "hunter22ish",
                confirmPassword = "hunter22ish",
                isSubmitting = true,
            ),
            onAction = {},
            onBack = {},
            onSignIn = {},
        )
    }
}

@Preview
@Composable
private fun SignUpScreenPreview_Error() {
    PreviewContent {
        SignUpScreen(
            state = SignUpState(
                email = "elijah@example.com",
                password = "hunter22ish",
                confirmPassword = "hunter22ish",
                error = SignUpError.Unknown,
            ),
            onAction = {},
            onBack = {},
            onSignIn = {},
        )
    }
}

@Preview
@Composable
private fun ForgotPasswordScreenPreview_Empty() {
    PreviewContent {
        ForgotPasswordScreen(
            state = ForgotPasswordState(),
            onAction = {},
            onBack = {},
            onBackToSignIn = {},
        )
    }
}

@Preview
@Composable
private fun ForgotPasswordScreenPreview_Submitting() {
    PreviewContent {
        ForgotPasswordScreen(
            state = ForgotPasswordState(
                email = "elijah@example.com",
                isSubmitting = true,
            ),
            onAction = {},
            onBack = {},
            onBackToSignIn = {},
        )
    }
}

@Preview
@Composable
private fun ForgotPasswordScreenPreview_Sent() {
    PreviewContent {
        ForgotPasswordScreen(
            state = ForgotPasswordState(
                email = "elijah@example.com",
                sent = true,
            ),
            onAction = {},
            onBack = {},
            onBackToSignIn = {},
        )
    }
}

@Preview
@Composable
private fun ForgotPasswordScreenPreview_RateLimited() {
    PreviewContent {
        ForgotPasswordScreen(
            state = ForgotPasswordState(
                email = "elijah@example.com",
                banner = ForgotPasswordState.Banner.RateLimited,
            ),
            onAction = {},
            onBack = {},
            onBackToSignIn = {},
        )
    }
}

@Preview
@Composable
private fun VerifyEmailScreenPreview_AwaitingConfirmation() {
    PreviewContent {
        VerifyEmailScreen(
            state = VerifyEmailState(email = "elijah@example.com"),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun VerifyEmailScreenPreview_StillPending() {
    PreviewContent {
        VerifyEmailScreen(
            state = VerifyEmailState(
                email = "elijah@example.com",
                banner = VerifyEmailState.Banner.StillPending,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun VerifyEmailScreenPreview_Refreshing() {
    PreviewContent {
        VerifyEmailScreen(
            state = VerifyEmailState(
                email = "elijah@example.com",
                isRefreshing = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun VerifyEmailScreenPreview_Resending() {
    PreviewContent {
        VerifyEmailScreen(
            state = VerifyEmailState(
                email = "elijah@example.com",
                isResending = true,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun VerifyEmailScreenPreview_ResendSent() {
    PreviewContent {
        VerifyEmailScreen(
            state = VerifyEmailState(
                email = "elijah@example.com",
                banner = VerifyEmailState.Banner.ResendSent,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun VerifyEmailScreenPreview_NetworkError() {
    PreviewContent {
        VerifyEmailScreen(
            state = VerifyEmailState(
                email = "elijah@example.com",
                banner = VerifyEmailState.Banner.NetworkError,
            ),
            onAction = {},
            onBack = {},
        )
    }
}

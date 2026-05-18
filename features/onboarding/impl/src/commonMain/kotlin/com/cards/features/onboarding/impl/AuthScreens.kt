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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.text.OutlinedTextField
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

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
                Button(onClick = onBack, style = ButtonStyle.Text) {
                    Text("← Back")
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
) {
    AuthShell(onBack = onBack) {
        Text(
            text = "Welcome back",
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.onSurfacePrimary,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = "Sign in to keep your progress across devices.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
        )

        Spacer(modifier = Modifier.height(Dimension.D900))

        EmailField(
            value = state.email,
            enabled = !state.isSubmitting,
            onChange = { onAction(SignInAction.EmailChanged(it)) },
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
        PasswordField(
            value = state.password,
            enabled = !state.isSubmitting,
            onChange = { onAction(SignInAction.PasswordChanged(it)) },
            imeAction = ImeAction.Go,
            onSubmitImeAction = { onAction(SignInAction.Submit) },
        )

        state.error?.let {
            Spacer(modifier = Modifier.height(Dimension.D400))
            ErrorText(it)
        }

        Spacer(modifier = Modifier.height(Dimension.D800))

        Button(
            onClick = { onAction(SignInAction.Submit) },
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSubmitting) "Signing in…" else "Sign in")
        }

        Spacer(modifier = Modifier.height(Dimension.D400))

        Button(
            onClick = onCreateAccount,
            style = ButtonStyle.Text,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Don't have an account? Create one")
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
    AuthShell(onBack = onBack) {
        Text(
            text = "Create your account",
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.onSurfacePrimary,
        )
        Spacer(modifier = Modifier.height(Dimension.D300))
        Text(
            text = "Save your chips + progress. We'll send a verification email.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
        )

        Spacer(modifier = Modifier.height(Dimension.D900))

        EmailField(
            value = state.email,
            enabled = !state.isSubmitting,
            onChange = { onAction(SignUpAction.EmailChanged(it)) },
        )
        Spacer(modifier = Modifier.height(Dimension.D500))
        PasswordField(
            value = state.password,
            enabled = !state.isSubmitting,
            onChange = { onAction(SignUpAction.PasswordChanged(it)) },
            imeAction = ImeAction.Go,
            onSubmitImeAction = { onAction(SignUpAction.Submit) },
            helper = "At least ${SignUpState.MIN_PASSWORD_LENGTH} characters",
        )

        state.error?.let {
            Spacer(modifier = Modifier.height(Dimension.D400))
            ErrorText(it)
        }

        Spacer(modifier = Modifier.height(Dimension.D800))

        Button(
            onClick = { onAction(SignUpAction.Submit) },
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isSubmitting) "Creating account…" else "Create account")
        }

        Spacer(modifier = Modifier.height(Dimension.D400))

        Button(
            onClick = onSignIn,
            style = ButtonStyle.Text,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Already have an account? Sign in")
        }
    }
}

@Composable
fun VerifyEmailScreen(
    state: VerifyEmailState,
    onAction: (VerifyEmailAction) -> Unit,
    onBack: () -> Unit,
) {
    AuthShell(onBack = onBack) {
        Text(
            text = "📧",
            typography = AppTheme.typography.Display.D900,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D600))

        Text(
            text = "Check your email",
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.onSurfacePrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Dimension.D400))

        Text(
            text = "We sent a verification link to ${state.email}. Tap the link, then come back here.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        state.banner?.let { banner ->
            Spacer(modifier = Modifier.height(Dimension.D500))
            BannerText(banner)
        }

        Spacer(modifier = Modifier.height(Dimension.D1000))

        Button(
            onClick = { onAction(VerifyEmailAction.IClickedTheLink) },
            enabled = !state.isRefreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isRefreshing) "Checking…" else "I confirmed, continue")
        }

        Spacer(modifier = Modifier.height(Dimension.D400))

        Button(
            onClick = { onAction(VerifyEmailAction.Resend) },
            style = ButtonStyle.Text,
            enabled = !state.isResending && !state.isRefreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isResending) "Sending…" else "Resend email")
        }
    }
}

// ---- Field helpers ----

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
        label = { Text("Email") },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PasswordField(
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    imeAction: ImeAction,
    onSubmitImeAction: () -> Unit,
    helper: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onGo = { onSubmitImeAction() }),
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
        )
        helper?.let {
            Spacer(modifier = Modifier.height(Dimension.D200))
            Text(
                text = it,
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
    }
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
private fun BannerText(banner: VerifyEmailState.Banner) {
    val (text, color) = when (banner) {
        VerifyEmailState.Banner.StillPending ->
            "Email not yet confirmed. Tap the link in your inbox first." to AppTheme.colors.onSurfaceSecondary
        VerifyEmailState.Banner.ResendSent ->
            "Verification email sent. Check your inbox." to AppTheme.colors.onSurfaceSecondary
        VerifyEmailState.Banner.ResendRateLimited ->
            "Too many resends in a row. Wait a minute and try again." to AppTheme.colors.danger
        VerifyEmailState.Banner.NetworkError ->
            "Couldn't reach the server. Check your connection." to AppTheme.colors.danger
        VerifyEmailState.Banner.GenericError ->
            "Something went wrong. Try again." to AppTheme.colors.danger
    }
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = color,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

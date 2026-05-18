package com.dangerfield.cards.features.profile.impl.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.dangerfield.cards.libraries.identity.OAuthProvider
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

/**
 * Renders the claim-account CTAs. When the underlying OAuth providers
 * haven't been enabled yet in the Supabase dashboard the screen is honest:
 * shows a "coming soon" message instead of buttons that throw on tap.
 *
 * The conflicting-provider state (Supabase says "this OAuth identity is
 * already on a different account") shows a destructive-style confirm path
 * — explicit, not implicit, per the locked V1 decision that we don't
 * auto-merge anonymous progress.
 */
@Composable
fun ClaimAccountScreen(
    state: ClaimAccountState,
    onAction: (ClaimAccountAction) -> Unit,
    onBack: () -> Unit,
) {
    Screen(
        contentWindowInsets = WindowInsets.systemBars,
        containerColor = AppTheme.colors.background.color,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Dimension.D800),
                verticalArrangement = Arrangement.Top,
            ) {
                Spacer(modifier = Modifier.height(Dimension.D200))
                Button(onClick = onBack, style = ButtonStyle.Text, enabled = !state.isSubmitting) {
                    Text("← Back")
                }
                Spacer(modifier = Modifier.height(Dimension.D700))

                Text(
                    text = "Claim your account",
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.onSurfacePrimary,
                )
                Spacer(modifier = Modifier.height(Dimension.D300))
                Text(
                    text = "Link Apple or Google so your chips, XP, and achievements " +
                        "follow you across devices and survive reinstall.",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                )

                Spacer(modifier = Modifier.height(Dimension.D900))

                if (!state.anyProviderEnabled) {
                    ComingSoonNotice()
                } else {
                    if (state.googleEnabled) {
                        ProviderButton(
                            label = "Continue with Google",
                            enabled = !state.isSubmitting,
                            onClick = { onAction(ClaimAccountAction.ClaimWith(OAuthProvider.Google)) },
                        )
                        Spacer(modifier = Modifier.height(Dimension.D400))
                    }
                    if (state.appleEnabled) {
                        ProviderButton(
                            label = "Continue with Apple",
                            enabled = !state.isSubmitting,
                            onClick = { onAction(ClaimAccountAction.ClaimWith(OAuthProvider.Apple)) },
                        )
                    }
                }

                state.error?.let {
                    Spacer(modifier = Modifier.height(Dimension.D600))
                    Text(
                        text = it,
                        typography = AppTheme.typography.Body.B500,
                        color = AppTheme.colors.danger,
                    )
                }

                state.conflictingProvider?.let { provider ->
                    Spacer(modifier = Modifier.height(Dimension.D500))
                    Button(
                        onClick = { onAction(ClaimAccountAction.ConfirmSwitchToExisting) },
                        enabled = !state.isSubmitting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Switch to my ${provider.label} account anyway")
                    }
                }

                Spacer(modifier = Modifier.height(Dimension.D800))
            }
        }
    }
}

@Composable
private fun ProviderButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label)
    }
}

@Composable
private fun ComingSoonNotice() {
    Text(
        text = "Sign in with Apple and Google are coming soon. " +
            "Your progress is already safe on this device — we'll let you back it up to the cloud here.",
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.onSurfaceSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

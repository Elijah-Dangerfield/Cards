package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop/preview target — renders the Compose fallback. */
@Composable
actual fun AppleSignInButton(
    modifier: Modifier,
    enabled: Boolean,
    isLoading: Boolean,
    kind: AppleSignInButtonKind,
    onClick: () -> Unit,
) = ComposeAppleSignInButton(modifier, enabled, isLoading, kind, onClick)

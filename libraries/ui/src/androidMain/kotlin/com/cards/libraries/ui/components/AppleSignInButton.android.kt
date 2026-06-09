package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Apple sign-in isn't offered on Android — this only renders in previews. */
@Composable
actual fun AppleSignInButton(
    modifier: Modifier,
    enabled: Boolean,
    isLoading: Boolean,
    kind: AppleSignInButtonKind,
    onClick: () -> Unit,
) = ComposeAppleSignInButton(modifier, enabled, isLoading, kind, onClick)

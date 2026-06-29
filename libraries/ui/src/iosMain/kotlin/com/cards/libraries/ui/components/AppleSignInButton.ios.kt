package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.dangerfield.cards.libraries.ui.nativeviews.LocalNativeViewFactory
import com.dangerfield.cards.libraries.ui.nativeviews.NativeAppleSignInButtonKind
import com.dangerfield.cards.libraries.ui.nativeviews.NativeAppleSignInButtonStyle

/**
 * Renders the system `ASAuthorizationAppleIDButton` through the native view
 * factory. Falls back to the Compose look-alike if no factory is in scope
 * (e.g. a preview running without the iOS app's `LocalNativeViewFactory`).
 *
 * `rememberUpdatedState` keeps the latest [onClick] flowing to the long-lived
 * native button without recreating it on every recomposition.
 */
@Composable
actual fun AppleSignInButton(
    modifier: Modifier,
    enabled: Boolean,
    isLoading: Boolean,
    kind: AppleSignInButtonKind,
    style: AppleSignInButtonStyle,
    onClick: () -> Unit,
) {
    val factory = LocalNativeViewFactory.current
    val latestOnClick by rememberUpdatedState(onClick)
    val effectiveEnabled = enabled && !isLoading

    if (factory == null) {
        ComposeAppleSignInButton(modifier, enabled, isLoading, kind, style, onClick)
        return
    }

    UIKitView(
        modifier = modifier.defaultMinSize(minHeight = 50.dp),
        factory = {
            val view = factory.createAppleSignInButton(
                kind = kind.toNativeKind(),
                style = style.toNativeStyle(),
                // Matches the app's button radius (Radii.Button = 16dp) so the
                // Apple button reads as part of the set, not a square slab.
                cornerRadius = 16f,
                onTap = { latestOnClick() },
            )
            factory.updateAppleSignInButton(view, effectiveEnabled) { latestOnClick() }
            view
        },
        update = { view ->
            factory.updateAppleSignInButton(view, effectiveEnabled) { latestOnClick() }
        },
    )
}

private fun AppleSignInButtonKind.toNativeKind(): NativeAppleSignInButtonKind = when (this) {
    AppleSignInButtonKind.SignIn -> NativeAppleSignInButtonKind.SignIn
    AppleSignInButtonKind.ContinueFlow -> NativeAppleSignInButtonKind.ContinueFlow
}

private fun AppleSignInButtonStyle.toNativeStyle(): NativeAppleSignInButtonStyle = when (this) {
    AppleSignInButtonStyle.Light -> NativeAppleSignInButtonStyle.White
    AppleSignInButtonStyle.Dark -> NativeAppleSignInButtonStyle.Black
}

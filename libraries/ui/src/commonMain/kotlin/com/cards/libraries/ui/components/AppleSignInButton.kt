package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme

/** `SignIn` → "Sign in with Apple"; `ContinueFlow` → "Continue with Apple" (onboarding). */
enum class AppleSignInButtonKind { SignIn, ContinueFlow }

/**
 * Which of Apple's two brand-fixed button surfaces to render — [Dark] (black
 * fill) on a dark page so it doesn't punch a white slab in; [Light] (white fill)
 * on a light page. Both are HIG-compliant; this is the only styling lever Apple
 * exposes.
 */
enum class AppleSignInButtonStyle { Light, Dark }

/**
 * "Sign in with Apple" button.
 *
 * On iOS this is the real `ASAuthorizationAppleIDButton` — system-drawn,
 * Apple-HIG-compliant, and the only button App Review accepts. On every other
 * target it falls back to a Compose look-alike, which in practice is only ever
 * seen in `@Preview`/tests: the Apple button isn't shown off-iOS (see
 * `docs/decisions.md`, "Apple sign-in").
 *
 * [isLoading] disables the button while a sign-in is in flight (the native
 * Apple button has no spinner of its own).
 */
@Composable
expect fun AppleSignInButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    kind: AppleSignInButtonKind = AppleSignInButtonKind.ContinueFlow,
    style: AppleSignInButtonStyle = AppleSignInButtonStyle.Light,
    onClick: () -> Unit,
)

/**
 * Compose-drawn fallback for non-iOS targets. Apple mandates a black or white
 * button — [style] picks which. Both are brand-fixed (Apple HIG), deliberately
 * not themed beyond that two-way choice.
 */
@Composable
internal fun ComposeAppleSignInButton(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    kind: AppleSignInButtonKind = AppleSignInButtonKind.ContinueFlow,
    style: AppleSignInButtonStyle = AppleSignInButtonStyle.Light,
    onClick: () -> Unit,
) {
    val interactive = enabled && !isLoading
    val dark = style == AppleSignInButtonStyle.Dark
    val surface = if (dark) ColorResource.Black else ColorResource.White
    val foreground = if (dark) ColorResource.White else ColorResource.Black
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(surface.color)
            .alpha(if (enabled) 1f else 0.6f)
            .semantics { role = Role.Button }
            .let { if (interactive) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp),
                strokeWidth = 2.dp,
                color = foreground.color,
            )
        } else {
            Text(
                text = when (kind) {
                    AppleSignInButtonKind.SignIn -> "Sign in with Apple"
                    AppleSignInButtonKind.ContinueFlow -> "Continue with Apple"
                },
                typography = AppTheme.typography.Body.B600,
                color = foreground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

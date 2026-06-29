package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.auth_sign_in_oauth_google_logo_a11y
import cards.libraries.resources.generated.resources.ic_google_g
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Which of Google's two brand-fixed sign-in surfaces to render. Both are valid
 * per Google's guidelines; pick the one that reads on the surrounding surface —
 * [Dark] on the app's dark theme so the button doesn't punch a white slab into
 * the page, [Light] when sitting on a light surface.
 */
enum class GoogleSignInButtonTheme { Light, Dark }

/**
 * "Continue with Google" button — the four-colour Google "G" mark beside a
 * label, on one of Google's two mandated neutral surfaces.
 *
 * Unlike [AppleSignInButton] there's no system-drawn Google equivalent, so this
 * is a single Compose composable across all targets. Both surfaces are
 * brand-fixed (Google's guidelines): [GoogleSignInButtonTheme.Light] is the
 * white button, [GoogleSignInButtonTheme.Dark] is the `#131314` button with a
 * hairline stroke — deliberately not themed beyond that two-way brand choice.
 * Only the disabled alpha and the spinner pull from DS tokens.
 *
 * [text] is passed in (typically a `stringResource`) so the user-facing copy
 * stays in `:libraries:resources`, not baked into this primitive.
 *
 * [isLoading] swaps the content for a spinner and blocks taps while a sign-in is
 * in flight.
 */
@Composable
fun GoogleSignInButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    theme: GoogleSignInButtonTheme = GoogleSignInButtonTheme.Light,
    onClick: () -> Unit,
) {
    val interactive = enabled && !isLoading
    val dark = theme == GoogleSignInButtonTheme.Dark
    val surface = if (dark) GoogleDarkSurface else ColorResource.White
    val foreground = if (dark) GoogleDarkForeground else ColorResource.Black
    val shape = Radii.Button.shape
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 50.dp)
            .clip(shape)
            .background(surface.color)
            .then(if (dark) Modifier.border(1.dp, GoogleDarkStroke.color, shape) else Modifier)
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Image(
                    painter = painterResource(Res.drawable.ic_google_g),
                    contentDescription = stringResource(Res.string.auth_sign_in_oauth_google_logo_a11y),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = text,
                    typography = AppTheme.typography.Body.B600,
                    color = foreground,
                )
            }
        }
    }
}

// Google's dark-theme sign-in button is brand-fixed (#131314 surface, #8E918F
// hairline, #E3E3E3 label) — provider brand constants, not app theme tokens, so
// they stay next to the primitive rather than in the semantic palette.
private val GoogleDarkSurface = ColorResource.FromColor(Color(0xFF131314), "google-dark-surface")
private val GoogleDarkStroke = ColorResource.FromColor(Color(0xFF8E918F), "google-dark-stroke")
private val GoogleDarkForeground = ColorResource.FromColor(Color(0xFFE3E3E3), "google-dark-foreground")

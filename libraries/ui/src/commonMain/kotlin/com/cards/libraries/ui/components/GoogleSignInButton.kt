package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.auth_sign_in_oauth_google_logo_a11y
import cards.libraries.resources.generated.resources.ic_google_g
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * "Continue with Google" button — the four-colour Google "G" mark beside a
 * label, on Google's mandated neutral surface.
 *
 * Unlike [AppleSignInButton] there's no system-drawn Google equivalent, so this
 * is a single Compose composable across all targets. It follows Google's
 * sign-in branding: the white (`light`) button variant with the full-colour G,
 * which reads correctly on the app's dark theme and sits visually next to the
 * adjacent white Apple button. The white surface + colour mark are brand-fixed
 * (Google's guidelines), deliberately not themed — only the disabled alpha and
 * the spinner pull from DS tokens.
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
    onClick: () -> Unit,
) {
    val interactive = enabled && !isLoading
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ColorResource.White.color)
            .alpha(if (enabled) 1f else 0.6f)
            .semantics { role = Role.Button }
            .let { if (interactive) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp),
                strokeWidth = 2.dp,
                color = ColorResource.Black.color,
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
                    color = ColorResource.Black,
                )
            }
        }
    }
}

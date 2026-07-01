package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.save_progress_cta
import cards.libraries.resources.generated.resources.save_progress_subtitle
import cards.libraries.resources.generated.resources.save_progress_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonAccent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The shared guest "Save your progress" sign-in nudge — a lock, a one-line
 * pitch, and a white "Sign in" pill on the flat cool-blue [BannerType.Trust] tint.
 *
 * One definition for every tab root that hosts it (Profile, Stats, Settings):
 * each caller just gates on "is this a guest?" and wires [onSignIn] to its
 * claim-account route. Purely presentational — it holds no auth state, so it
 * lives in the DS rather than an auth feature.
 */
@Composable
fun SaveProgressBanner(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Banner(
        type = BannerType.Trust,
        modifier = modifier,
        // The whole card is a tap target — not just the pill — so a guest can
        // tap anywhere to start claiming their account.
        onClick = onSignIn,
        leading = {
            Icon(
                icon = Icons.Lock(null),
                size = IconSize.Small,
                color = AppTheme.colors.content,
            )
        },
        title = { Text(stringResource(Res.string.save_progress_title)) },
        body = { Text(stringResource(Res.string.save_progress_subtitle)) },
        action = {
            // White pill — a clear, high-contrast call to action on the flat tint.
            ButtonPrimary(
                onClick = onSignIn,
                accent = ButtonAccent.Inverse,
                size = ButtonSize.Small,
            ) {
                Text(stringResource(Res.string.save_progress_cta))
            }
        },
    )
}

@Preview
@Composable
private fun SaveProgressBannerPreview() {
    PreviewContent {
        SaveProgressBanner(onSignIn = {})
    }
}

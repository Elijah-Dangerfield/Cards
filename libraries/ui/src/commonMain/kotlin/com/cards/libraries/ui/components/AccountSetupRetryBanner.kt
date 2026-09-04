package com.dangerfield.cards.libraries.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.account_setup_banner_retry
import cards.libraries.resources.generated.resources.account_setup_banner_retrying
import cards.libraries.resources.generated.resources.account_setup_retry_banner_body
import cards.libraries.resources.generated.resources.account_setup_retry_banner_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * In-page mirror of the app-shell account-setup banner, for the tab roots a
 * degraded guest naturally visits (Profile, Settings) once they want to save
 * progress. The shell strip scrolls off the top; this gives the same Retry
 * affordance right next to the [SaveProgressBanner] sign-in nudge.
 *
 * Purely presentational on the [BannerType.Warning] surface — it holds no
 * creation state. Callers gate visibility on [LocalAccountSetupRetry] and wire
 * [onRetry] / [isRetrying] from there.
 */
@Composable
fun AccountSetupRetryBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false,
) {
    Banner(
        type = BannerType.Warning,
        modifier = modifier,
        leading = {
            Icon(
                icon = Icons.Refresh(null),
                size = IconSize.Small,
                color = AppTheme.colors.content,
            )
        },
        title = { Text(stringResource(Res.string.account_setup_retry_banner_title)) },
        body = { Text(stringResource(Res.string.account_setup_retry_banner_body)) },
        action = {
            if (isRetrying) {
                Text(
                    text = stringResource(Res.string.account_setup_banner_retrying),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )
            } else {
                ButtonSecondary(onClick = onRetry, size = ButtonSize.Small) {
                    Text(stringResource(Res.string.account_setup_banner_retry))
                }
            }
        },
    )
}

@Preview
@Composable
private fun AccountSetupRetryBannerPreview() {
    PreviewContent {
        AccountSetupRetryBanner(onRetry = {})
    }
}

@Preview
@Composable
private fun AccountSetupRetryBannerPreview_Retrying() {
    PreviewContent {
        AccountSetupRetryBanner(onRetry = {}, isRetrying = true)
    }
}

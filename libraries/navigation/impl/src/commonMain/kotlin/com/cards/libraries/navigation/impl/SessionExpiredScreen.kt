package com.dangerfield.cards.libraries.navigation.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.session_expired_guest_logout_confirm
import cards.libraries.resources.generated.resources.session_expired_guest_logout_confirm_message
import cards.libraries.resources.generated.resources.session_expired_guest_logout_confirm_title
import cards.libraries.resources.generated.resources.session_expired_guest_logout_keep_playing
import cards.libraries.resources.generated.resources.session_expired_guest_message
import cards.libraries.resources.generated.resources.session_expired_guest_title
import cards.libraries.resources.generated.resources.session_expired_logout
import cards.libraries.resources.generated.resources.session_expired_message
import cards.libraries.resources.generated.resources.session_expired_retry
import cards.libraries.resources.generated.resources.session_expired_retry_failed
import cards.libraries.resources.generated.resources.session_expired_title
import com.dangerfield.cards.libraries.core.doNothing
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.CircularLoadingIndicator
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SessionExpiredScreen(
    wasAnonymous: Boolean,
    retrying: Boolean,
    retryFailed: Boolean,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
) {
    BackHandler { doNothing() }

    var confirmingLogout by remember { mutableStateOf(false) }

    Screen { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = Dimension.D1000),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = stringResource(
                    if (wasAnonymous) Res.string.session_expired_guest_title
                    else Res.string.session_expired_title,
                ),
                typography = AppTheme.typography.Display.D1000,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Dimension.D500))

            Text(
                text = stringResource(
                    if (wasAnonymous) Res.string.session_expired_guest_message
                    else Res.string.session_expired_message,
                ),
                typography = AppTheme.typography.Body.B400,
                textAlign = TextAlign.Center,
            )

            if (retryFailed) {
                Spacer(modifier = Modifier.height(Dimension.D400))
                Text(
                    text = stringResource(Res.string.session_expired_retry_failed),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.warning,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = Modifier.weight(2f))

            ButtonPrimary(
                size = ButtonSize.Large,
                enabled = !retrying,
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (retrying) {
                    CircularLoadingIndicator()
                } else {
                    Text(text = stringResource(Res.string.session_expired_retry))
                }
            }

            Spacer(modifier = Modifier.height(Dimension.D500))

            ButtonSecondary(
                size = ButtonSize.Large,
                enabled = !retrying,
                onClick = { if (wasAnonymous) confirmingLogout = true else onLogout() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.session_expired_logout))
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    if (confirmingLogout) {
        Dialog(
            title = stringResource(Res.string.session_expired_guest_logout_confirm_title),
            description = stringResource(Res.string.session_expired_guest_logout_confirm_message),
            primaryButtonText = stringResource(Res.string.session_expired_guest_logout_keep_playing),
            secondaryButtonText = stringResource(Res.string.session_expired_guest_logout_confirm),
            onPrimaryButtonClicked = { confirmingLogout = false },
            onSecondaryButtonClicked = {
                confirmingLogout = false
                onLogout()
            },
            onDismissRequest = { confirmingLogout = false },
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Claimed() {
    PreviewContent {
        SessionExpiredScreen(
            wasAnonymous = false,
            retrying = false,
            retryFailed = false,
            onRetry = {},
            onLogout = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Retrying() {
    PreviewContent {
        SessionExpiredScreen(
            wasAnonymous = false,
            retrying = true,
            retryFailed = false,
            onRetry = {},
            onLogout = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_RetryFailed() {
    PreviewContent {
        SessionExpiredScreen(
            wasAnonymous = false,
            retrying = false,
            retryFailed = true,
            onRetry = {},
            onLogout = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Guest() {
    PreviewContent {
        SessionExpiredScreen(
            wasAnonymous = true,
            retrying = false,
            retryFailed = false,
            onRetry = {},
            onLogout = {},
        )
    }
}

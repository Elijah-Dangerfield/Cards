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
import cards.libraries.resources.generated.resources.session_expired_abandon_message
import cards.libraries.resources.generated.resources.session_expired_abandon_sign_in_instead
import cards.libraries.resources.generated.resources.session_expired_abandon_start_over
import cards.libraries.resources.generated.resources.session_expired_abandon_title
import cards.libraries.resources.generated.resources.session_expired_abandon_title_named
import cards.libraries.resources.generated.resources.session_expired_guest_logout_confirm
import cards.libraries.resources.generated.resources.session_expired_guest_logout_confirm_message
import cards.libraries.resources.generated.resources.session_expired_guest_logout_confirm_title
import cards.libraries.resources.generated.resources.session_expired_guest_logout_keep_playing
import cards.libraries.resources.generated.resources.session_expired_guest_message
import cards.libraries.resources.generated.resources.session_expired_guest_title
import cards.libraries.resources.generated.resources.session_expired_logout
import cards.libraries.resources.generated.resources.session_expired_offline_message
import cards.libraries.resources.generated.resources.session_expired_offline_title
import cards.libraries.resources.generated.resources.session_expired_recover_message
import cards.libraries.resources.generated.resources.session_expired_recover_message_named
import cards.libraries.resources.generated.resources.session_expired_recover_sign_in
import cards.libraries.resources.generated.resources.session_expired_recover_start_over
import cards.libraries.resources.generated.resources.session_expired_recover_title
import cards.libraries.resources.generated.resources.session_expired_retry
import cards.libraries.resources.generated.resources.session_expired_retry_failed
import com.dangerfield.cards.libraries.core.doNothing
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.CircularLoadingIndicator
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonGhost
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * The blocking screen for a lost session.
 *
 * Three shapes, one per [SessionRecovery]. The one that matters is
 * [SessionRecovery.SignInToRestore]: it names the account waiting on the
 * server and leads with signing in, because the version of this screen that
 * only offered "retry" and "log out" is how a real user ended up creating a
 * second account twelve seconds after we correctly refused to create one for
 * them (AUTH-34).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SessionExpiredScreen(
    recovery: SessionRecovery,
    retrying: Boolean,
    retryFailed: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onAbandon: () -> Unit,
) {
    BackHandler { doNothing() }

    var confirmingAbandon by remember { mutableStateOf(false) }

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
                text = stringResource(recovery.title()),
                typography = AppTheme.typography.Display.D1000,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(Dimension.D500))

            Text(
                text = recovery.message(),
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

            when (recovery) {
                is SessionRecovery.SignInToRestore -> {
                    ButtonPrimary(
                        size = ButtonSize.Large,
                        enabled = !retrying,
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(Res.string.session_expired_recover_sign_in))
                    }

                    Spacer(modifier = Modifier.height(Dimension.D500))

                    RetryButton(
                        type = ButtonType.Secondary,
                        retrying = retrying,
                        onRetry = onRetry,
                    )

                    Spacer(modifier = Modifier.height(Dimension.D300))

                    ButtonGhost(
                        size = ButtonSize.Large,
                        enabled = !retrying,
                        onClick = { confirmingAbandon = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(Res.string.session_expired_recover_start_over))
                    }
                }

                SessionRecovery.GuestOnly -> {
                    RetryButton(
                        type = ButtonType.Primary,
                        retrying = retrying,
                        onRetry = onRetry,
                    )

                    Spacer(modifier = Modifier.height(Dimension.D500))

                    ButtonSecondary(
                        size = ButtonSize.Large,
                        enabled = !retrying,
                        onClick = { confirmingAbandon = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(Res.string.session_expired_logout))
                    }
                }

                // Nothing is confirmed lost, so there is nothing to walk away
                // from yet. The mode flips as soon as the connection is back.
                SessionRecovery.Offline -> RetryButton(
                    type = ButtonType.Primary,
                    retrying = retrying,
                    onRetry = onRetry,
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    if (confirmingAbandon) {
        when (recovery) {
            is SessionRecovery.SignInToRestore -> Dialog(
                title = recovery.displayName
                    ?.let { stringResource(Res.string.session_expired_abandon_title_named, it) }
                    ?: stringResource(Res.string.session_expired_abandon_title),
                description = stringResource(Res.string.session_expired_abandon_message),
                primaryButtonText = stringResource(Res.string.session_expired_abandon_sign_in_instead),
                secondaryButtonText = stringResource(Res.string.session_expired_abandon_start_over),
                onPrimaryButtonClicked = {
                    confirmingAbandon = false
                    onSignIn()
                },
                onSecondaryButtonClicked = {
                    confirmingAbandon = false
                    onAbandon()
                },
                onDismissRequest = { confirmingAbandon = false },
            )

            SessionRecovery.GuestOnly -> Dialog(
                title = stringResource(Res.string.session_expired_guest_logout_confirm_title),
                description = stringResource(Res.string.session_expired_guest_logout_confirm_message),
                primaryButtonText = stringResource(Res.string.session_expired_guest_logout_keep_playing),
                secondaryButtonText = stringResource(Res.string.session_expired_guest_logout_confirm),
                onPrimaryButtonClicked = { confirmingAbandon = false },
                onSecondaryButtonClicked = {
                    confirmingAbandon = false
                    onAbandon()
                },
                onDismissRequest = { confirmingAbandon = false },
            )

            // Losing the connection with the confirmation open takes the
            // question away rather than answering it with the wrong copy.
            SessionRecovery.Offline -> doNothing()
        }
    }
}

@Composable
private fun RetryButton(
    type: ButtonType,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    Button(
        type = type,
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
}

private fun SessionRecovery.title() = when (this) {
    SessionRecovery.Offline -> Res.string.session_expired_offline_title
    is SessionRecovery.SignInToRestore -> Res.string.session_expired_recover_title
    SessionRecovery.GuestOnly -> Res.string.session_expired_guest_title
}

@Composable
private fun SessionRecovery.message(): String = when (this) {
    SessionRecovery.Offline -> stringResource(Res.string.session_expired_offline_message)
    is SessionRecovery.SignInToRestore -> displayName
        ?.let { stringResource(Res.string.session_expired_recover_message_named, it) }
        ?: stringResource(Res.string.session_expired_recover_message)
    SessionRecovery.GuestOnly -> stringResource(Res.string.session_expired_guest_message)
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Named() {
    PreviewContent {
        SessionExpiredScreen(
            recovery = SessionRecovery.SignInToRestore(displayName = "LuckyJack66"),
            retrying = false,
            retryFailed = false,
            onRetry = {},
            onSignIn = {},
            onAbandon = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Unnamed() {
    PreviewContent {
        SessionExpiredScreen(
            recovery = SessionRecovery.SignInToRestore(displayName = null),
            retrying = false,
            retryFailed = false,
            onRetry = {},
            onSignIn = {},
            onAbandon = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Retrying() {
    PreviewContent {
        SessionExpiredScreen(
            recovery = SessionRecovery.SignInToRestore(displayName = "LuckyJack66"),
            retrying = true,
            retryFailed = false,
            onRetry = {},
            onSignIn = {},
            onAbandon = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_RetryFailed() {
    PreviewContent {
        SessionExpiredScreen(
            recovery = SessionRecovery.GuestOnly,
            retrying = false,
            retryFailed = true,
            onRetry = {},
            onSignIn = {},
            onAbandon = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Guest() {
    PreviewContent {
        SessionExpiredScreen(
            recovery = SessionRecovery.GuestOnly,
            retrying = false,
            retryFailed = false,
            onRetry = {},
            onSignIn = {},
            onAbandon = {},
        )
    }
}

@Preview
@Composable
private fun SessionExpiredScreenPreview_Offline() {
    PreviewContent {
        SessionExpiredScreen(
            recovery = SessionRecovery.Offline,
            retrying = false,
            retryFailed = false,
            onRetry = {},
            onSignIn = {},
            onAbandon = {},
        )
    }
}

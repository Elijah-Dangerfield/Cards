package com.dangerfield.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.account_setup_banner_message
import cards.libraries.resources.generated.resources.account_setup_banner_retry
import cards.libraries.resources.generated.resources.account_setup_banner_retrying
import cards.libraries.resources.generated.resources.account_setup_explainer_body
import cards.libraries.resources.generated.resources.account_setup_explainer_confirm
import cards.libraries.resources.generated.resources.account_setup_explainer_title
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Top-of-screen banner shown while a guest account is still being created in the
 * background after the user finished onboarding offline
 * ([AccountCreationState.Failed]). Sits in the Scaffold's `topBar` slot
 * alongside [OfflineBanner] / [AppGuardBanner].
 *
 * Communicates the degraded state without blocking the user — they can keep
 * playing bots; [GuestAccountCreator] retries automatically on reconnect /
 * relaunch, and the banner disappears once creation succeeds. A manual **Retry**
 * button drives [GuestAccountCreator.retry] for users who don't want to wait for
 * the next automatic attempt.
 *
 * Once a failure has been seen the banner stays up through the resulting
 * retry ([AccountCreationState.InProgress]) — showing a "Retrying…" state
 * instead of flickering away and back — and only clears on
 * [AccountCreationState.Succeeded]. The first, never-failed creation pass
 * (Idle → InProgress → Succeeded) never shows the banner.
 */
@Composable
fun AccountSetupBanner(creator: GuestAccountCreator) {
    val status = rememberAccountSetupStatus(creator)
    AnimatedVisibility(
        visible = status.pending,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        AccountSetupBannerContent(
            isRetrying = status.isRetrying,
            onRetry = { creator.retry() },
        )
    }
}

/**
 * Pending / retrying snapshot of [GuestAccountCreator] for the banner host.
 * [pending] mirrors the once-failed gating ([AccountSetupBanner]'s KDoc): the
 * first never-failed creation pass never reads as pending.
 */
internal data class AccountSetupStatus(
    val pending: Boolean,
    val isRetrying: Boolean,
)

/**
 * Tracks the once-failed gating so both [AccountSetupBanner] and the combined
 * offline banner host ([AppStatusBanners]) decide visibility off one source.
 */
@Composable
internal fun rememberAccountSetupStatus(creator: GuestAccountCreator): AccountSetupStatus {
    val state by creator.state.collectAsState()
    var hasFailed by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is AccountCreationState.Failed -> hasFailed = true
            AccountCreationState.Succeeded -> hasFailed = false
            else -> Unit
        }
    }
    return AccountSetupStatus(
        pending = hasFailed && state !is AccountCreationState.Succeeded,
        isRetrying = hasFailed && state is AccountCreationState.InProgress,
    )
}

@Composable
internal fun AccountSetupBannerContent(
    isRetrying: Boolean,
    onRetry: () -> Unit,
) {
    val warning = AppTheme.colors.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(warning.color.copy(alpha = 0.18f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon = Icons.Refresh(null),
            color = warning,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(Res.string.account_setup_banner_message),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        if (isRetrying) {
            Text(
                text = stringResource(Res.string.account_setup_banner_retrying),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        } else {
            Button(
                onClick = onRetry,
                size = ButtonSize.Small,
                style = ButtonStyle.Text,
            ) {
                Text(stringResource(Res.string.account_setup_banner_retry))
            }
        }
    }
}

/**
 * The richer first-contact surface for the degraded-account state. The thin
 * [AccountSetupBanner] is the *standing* reminder, but the first time creation
 * is left pending it's easy to miss — so this one-time dialog explains what's
 * happening (play is safe, setup continues in the background, MP + purchases are
 * paused) before the banner takes over for the rest of the wait.
 *
 * Shown when [shouldShowAccountSetupExplainer] is true off the live pending
 * status and the persisted `accountSetupExplainerSeen` flag. Dismissing sets the
 * flag — so it only ever shows once per device — and leaves [AccountSetupBanner]
 * in place to keep the state visible until creation succeeds.
 */
@Composable
fun AccountSetupExplainerDialog(
    creator: GuestAccountCreator,
    appCache: AppCache,
) {
    val status = rememberAccountSetupStatus(creator)
    val appData by appCache.updates.collectAsState(initial = null)
    val seen = appData?.accountSetupExplainerSeen
    val scope = rememberCoroutineScope()
    if (seen != null && shouldShowAccountSetupExplainer(pending = status.pending, hasSeenExplainer = seen)) {
        val dismiss: () -> Unit = {
            scope.launch { appCache.update { it.copy(accountSetupExplainerSeen = true) } }
        }
        Dialog(
            title = stringResource(Res.string.account_setup_explainer_title),
            description = stringResource(Res.string.account_setup_explainer_body),
            primaryButtonText = stringResource(Res.string.account_setup_explainer_confirm),
            onDismissRequest = dismiss,
            onPrimaryButtonClicked = dismiss,
        )
    }
}

/**
 * Pure gate for the one-time explainer dialog: show it the first time account
 * creation is left pending and the user hasn't already seen it. Extracted so the
 * decision is unit-testable without Compose.
 */
internal fun shouldShowAccountSetupExplainer(
    pending: Boolean,
    hasSeenExplainer: Boolean,
): Boolean = pending && !hasSeenExplainer

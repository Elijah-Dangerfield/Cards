package com.dangerfield.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.account_setup_banner_retry
import cards.libraries.resources.generated.resources.account_setup_explainer_body
import cards.libraries.resources.generated.resources.account_setup_explainer_confirm
import cards.libraries.resources.generated.resources.account_setup_explainer_title
import cards.libraries.resources.generated.resources.account_setup_pill
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Top-of-screen status shown while a guest account is still being created in the
 * background after the user finished onboarding offline
 * ([AccountCreationState.Failed]). Sits in the Scaffold's `topBar` slot
 * alongside [OfflineBanner] / [AppGuardBanner].
 *
 * A compact tappable [StatusPill] ("Finishing setup") rather than a full-width
 * strip — tapping opens an explainer dialog that carries the detail and a Retry
 * action, so the standing reminder stays out of the way. The user can keep
 * playing bots; [GuestAccountCreator] retries automatically on reconnect /
 * relaunch, and the pill disappears once creation succeeds.
 *
 * Once a failure has been seen the pill stays up through the resulting retry
 * ([AccountCreationState.InProgress]) — Retry is simply hidden in the dialog
 * while an attempt is already in flight — and only clears on
 * [AccountCreationState.Succeeded]. The first, never-failed creation pass
 * (Idle → InProgress → Succeeded) never shows the pill.
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
    var explainerOpen by remember { mutableStateOf(false) }
    StatusPill(
        icon = Icons.Refresh(null),
        label = stringResource(Res.string.account_setup_pill),
        onClick = { explainerOpen = true },
    )
    if (explainerOpen) {
        Dialog(
            title = stringResource(Res.string.account_setup_explainer_title),
            description = stringResource(Res.string.account_setup_explainer_body),
            primaryButtonText = stringResource(Res.string.account_setup_explainer_confirm),
            onDismissRequest = { explainerOpen = false },
            onPrimaryButtonClicked = { explainerOpen = false },
            // Retry is the one thing the user can do to speed this up; hidden while
            // an attempt is already in flight (there'd be nothing to trigger).
            secondaryButtonText = if (isRetrying) null else stringResource(Res.string.account_setup_banner_retry),
            onSecondaryButtonClicked = if (isRetrying) {
                null
            } else {
                {
                    explainerOpen = false
                    onRetry()
                }
            },
        )
    }
}

/**
 * The richer first-contact surface for the degraded-account state. The compact
 * [AccountSetupBanner] pill is the *standing* reminder, but the first time
 * creation is left pending it's easy to miss — so this one-time dialog explains
 * what's happening (play is safe, setup continues in the background, MP +
 * purchases are paused) before the pill takes over for the rest of the wait.
 *
 * Shown when [shouldShowAccountSetupExplainer] is true off the live pending
 * status and the persisted `accountSetupExplainerSeen` flag. Dismissing sets the
 * flag — so it only ever shows once per device — and leaves the pill in place to
 * keep the state visible (and tappable for the same explainer) until creation
 * succeeds.
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

package com.dangerfield.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.app_offline_account_setup_banner
import cards.libraries.resources.generated.resources.app_offline_explainer_title
import cards.libraries.resources.generated.resources.app_offline_pill
import cards.libraries.resources.generated.resources.app_status_explainer_dismiss
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Host for the two transient top-of-screen status chips — the generic
 * [OfflineBanner] and the degraded-account [AccountSetupBanner] — that
 * dedupes the one scenario where both would otherwise stack: a first-launch
 * account creation that's pending *because* the device is offline.
 *
 * Each is a compact, tappable [StatusPill] rather than a full-width strip of
 * copy — a small tinted chip that opens an explainer dialog on tap, so the
 * detail lives one tap away instead of eating the top of every screen. When
 * offline AND the account is pending, we render a single combined pill instead
 * of stacking two. [AppGuardBanner] stays separate — it's a different
 * (maintenance / force-upgrade) concern, not a connection state.
 */
@Composable
fun AppStatusBanners(creator: GuestAccountCreator) {
    val isOffline by LocalAppState.current.isOffline.collectAsState()
    val setup = rememberAccountSetupStatus(creator)
    val combined = isOffline && setup.pending
    Column {
        AnimatedVisibility(
            visible = combined,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            OfflineAccountSetupBannerContent()
        }
        AnimatedVisibility(
            visible = isOffline && !combined,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            OfflineBannerContent()
        }
        AnimatedVisibility(
            visible = setup.pending && !combined,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            AccountSetupBannerContent(
                isRetrying = setup.isRetrying,
                onRetry = { creator.retry() },
            )
        }
    }
}

/**
 * Single pill for the offline + account-pending overlap. No Retry affordance —
 * the only thing that resolves it is reconnecting, which [GuestAccountCreator]
 * already retries on automatically; the explainer says so.
 */
@Composable
private fun OfflineAccountSetupBannerContent() {
    var explainerOpen by remember { mutableStateOf(false) }
    StatusPill(
        icon = Icons.CloudOff(null),
        label = stringResource(Res.string.app_offline_pill),
        onClick = { explainerOpen = true },
    )
    if (explainerOpen) {
        Dialog(
            title = stringResource(Res.string.app_offline_explainer_title),
            description = stringResource(Res.string.app_offline_account_setup_banner),
            primaryButtonText = stringResource(Res.string.app_status_explainer_dismiss),
            onDismissRequest = { explainerOpen = false },
            onPrimaryButtonClicked = { explainerOpen = false },
        )
    }
}

/**
 * The compact, tappable status chip every top-of-screen banner now renders — a
 * small tinted pill (icon + one short word) instead of a full-width strip of
 * copy. Tapping opens the matching explainer dialog, keeping the detail one tap
 * away rather than obstructing the top of the screen.
 */
@Composable
internal fun StatusPill(
    icon: IconResource,
    label: String,
    onClick: () -> Unit,
) {
    val warning = AppTheme.colors.warning
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(Radii.Round.shape)
                .background(warning.color.copy(alpha = 0.18f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon = icon, color = warning, size = IconSize.Smallest)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                typography = AppTheme.typography.Label.L400,
                color = AppTheme.colors.content,
            )
        }
    }
}

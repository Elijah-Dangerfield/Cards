package com.dangerfield.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.app_offline_account_setup_banner
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource

/**
 * Host for the two transient top-of-screen status strips — the generic
 * [OfflineBanner] and the degraded-account [AccountSetupBanner] — that
 * dedupes the one scenario where both would otherwise stack: a first-launch
 * account creation that's pending *because* the device is offline.
 *
 * Stacking both there wastes a lot of vertical space restating one situation
 * ("you're offline" + "we'll retry account setup"). When offline AND the
 * account is pending, we render a single scenario-specific line instead and
 * suppress the two standalone banners. Outside that overlap each banner shows
 * on its own exactly as before. [AppGuardBanner] stays separate — it's a
 * different (maintenance / force-upgrade) concern, not a connection state.
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
 * Single strip for the offline + account-pending overlap. No Retry affordance
 * — the only thing that resolves it is reconnecting, which [GuestAccountCreator]
 * already retries on automatically.
 */
@Composable
private fun OfflineAccountSetupBannerContent() {
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
            icon = Icons.CloudOff(null),
            color = warning,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(Res.string.app_offline_account_setup_banner),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Start,
        )
    }
}

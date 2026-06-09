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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

/**
 * Top-of-screen banner shown while a guest account is still being created in the
 * background after the user finished onboarding offline
 * ([AccountCreationState.Failed]). Sits in the Scaffold's `topBar` slot
 * alongside [OfflineBanner] / [AppGuardBanner].
 *
 * Communicates the degraded state without blocking the user — they can keep
 * playing bots; [GuestAccountCreator] retries automatically on reconnect /
 * relaunch, and the banner disappears once creation succeeds.
 */
@Composable
fun AccountSetupBanner(creator: GuestAccountCreator) {
    val state by creator.state.collectAsState()
    AnimatedVisibility(
        visible = state is AccountCreationState.Failed,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        AccountSetupBannerContent()
    }
}

@Composable
private fun AccountSetupBannerContent() {
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
            text = "Finishing account setup — we'll keep trying. Some features are limited until then.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Start,
        )
    }
}

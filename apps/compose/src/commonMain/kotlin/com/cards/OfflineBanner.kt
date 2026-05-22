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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import com.dangerfield.cards.system.AppTheme

/**
 * Top-of-screen banner shown whenever [LocalAppState]'s `isOffline` is
 * true. Sits in the Scaffold's `topBar` slot alongside [AppGuardBanner]
 * so the Scaffold owns status-bar inset propagation.
 *
 * Subtle by design — a thin tinted strip + icon + one line of copy. The
 * user can still navigate; this just sets expectations about what won't
 * work.
 */
@Composable
fun OfflineBanner() {
    val isOffline by LocalAppState.current.isOffline.collectAsState()
    AnimatedVisibility(
        visible = isOffline,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        OfflineBannerContent()
    }
}

@Composable
private fun OfflineBannerContent() {
    val warning = AppTheme.colors.status.warning.color
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(warning.copy(alpha = 0.18f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = warning,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Offline — some features unavailable.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Start,
        )
    }
}

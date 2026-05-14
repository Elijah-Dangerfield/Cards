package com.dangerfield.cards.features.upgrade.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.features.upgrade.AppGuardState
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

/**
 * Renders the right modal layer for the current [AppGuardState] above whatever
 * the app's nav graph is drawing. Place this once inside the app's root, after
 * the nav graph and after the dialog host, so it can cover everything.
 *
 * [content] is the rest of the app (typically the nav graph). It is drawn at
 * all times; the guard layer just covers it when needed.
 */
@Composable
fun AppGuardLayer(
    state: AppGuardState,
    onOpenStore: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        AnimatedVisibility(
            visible = state is AppGuardState.MaintenanceBanner,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val banner = state as? AppGuardState.MaintenanceBanner
            if (banner != null) MaintenanceBanner(message = banner.message)
        }

        AnimatedVisibility(
            visible = state is AppGuardState.MaintenanceBlocking || state is AppGuardState.UpgradeRequired,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            when (state) {
                is AppGuardState.UpgradeRequired -> UpgradeRequiredOverlay(onOpenStore = onOpenStore)
                is AppGuardState.MaintenanceBlocking -> MaintenanceBlockingOverlay(message = state.message)
                else -> Unit
            }
        }
    }
}

@Composable
private fun MaintenanceBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UpgradeRequiredOverlay(onOpenStore: () -> Unit) {
    BlockingOverlay {
        Text(
            text = "Time to update",
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This version of Cards is no longer supported. Grab the latest from your app store to keep playing.",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenStore) {
            Text("Update Cards")
        }
    }
}

@Composable
private fun MaintenanceBlockingOverlay(message: String) {
    BlockingOverlay {
        Text(
            text = "We'll be right back",
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BlockingOverlay(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background.color),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(20.dp))
                .background(AppTheme.colors.surfacePrimary.color)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            content()
        }
    }
}

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
import com.dangerfield.cards.libraries.core.BuildInfo
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
    onClearOverrides: () -> Unit = {},
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
                is AppGuardState.UpgradeRequired -> UpgradeRequiredOverlay(
                    onOpenStore = onOpenStore,
                    onClearOverrides = onClearOverrides,
                )
                is AppGuardState.MaintenanceBlocking -> MaintenanceBlockingOverlay(
                    message = state.message,
                    onClearOverrides = onClearOverrides,
                )
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
private fun UpgradeRequiredOverlay(onOpenStore: () -> Unit, onClearOverrides: () -> Unit) {
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
        DebugEscapeHatch(onClearOverrides = onClearOverrides)
    }
}

@Composable
private fun MaintenanceBlockingOverlay(message: String, onClearOverrides: () -> Unit) {
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
        DebugEscapeHatch(onClearOverrides = onClearOverrides)
    }
}

@Composable
private fun DebugEscapeHatch(onClearOverrides: () -> Unit) {
    if (!BuildInfo.isDebug) return
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        text = "Debug only: this overlay can be triggered by a QA override.",
        typography = AppTheme.typography.Body.B400,
        color = AppTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = onClearOverrides) {
        Text("Clear overrides")
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

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun AppGuardLayerPreview_Normal() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        AppGuardLayer(state = AppGuardState.Normal, onOpenStore = {}) {
            Box(
                modifier = Modifier.fillMaxSize().background(AppTheme.colors.background.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "App content",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.text,
                )
            }
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun AppGuardLayerPreview_Banner() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        AppGuardLayer(
            state = AppGuardState.MaintenanceBanner("Servers degraded, hands may be slower."),
            onOpenStore = {},
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(AppTheme.colors.background.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "App content",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.text,
                )
            }
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun AppGuardLayerPreview_MaintenanceBlocking() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        AppGuardLayer(
            state = AppGuardState.MaintenanceBlocking(
                "We're updating the server. Back in about 15 minutes.",
            ),
            onOpenStore = {},
        ) { }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun AppGuardLayerPreview_UpgradeRequired() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        AppGuardLayer(
            state = AppGuardState.UpgradeRequired,
            onOpenStore = {},
        ) { }
    }
}

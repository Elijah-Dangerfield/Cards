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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.features.upgrade.AppGuardState
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.ui.components.button.ButtonDanger
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.icon.Icon
import com.dangerfield.cards.libraries.ui.components.icon.IconSize
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.upgrade_debug_clear_overrides
import cards.libraries.resources.generated.resources.upgrade_maintenance_blocking_title
import cards.libraries.resources.generated.resources.upgrade_required_body
import cards.libraries.resources.generated.resources.upgrade_required_cta
import cards.libraries.resources.generated.resources.upgrade_required_reassurance
import cards.libraries.resources.generated.resources.upgrade_required_title
import org.jetbrains.compose.resources.stringResource

/**
 * Renders blocking overlays (upgrade-required, maintenance) for the current
 * [AppGuardState] above whatever the app's nav graph is drawing. Place this
 * once inside the app's root, after the nav graph and after the dialog host,
 * so it can cover everything.
 *
 * The non-blocking maintenance banner is rendered separately by
 * [AppGuardBanner] so it can sit inside the app's [Scaffold] `topBar` slot
 * and inherit the same status-bar inset model as the rest of the chrome.
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

/**
 * Renders the non-blocking maintenance banner if [state] is
 * [AppGuardState.MaintenanceBanner]. Designed to be placed in a `Scaffold`'s
 * `topBar` slot so the Scaffold owns status-bar inset propagation — putting
 * the banner outside the Scaffold causes it to dip under the status bar.
 */
@Composable
fun AppGuardBanner(state: AppGuardState) {
    AnimatedVisibility(
        visible = state is AppGuardState.MaintenanceBanner,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val banner = state as? AppGuardState.MaintenanceBanner
        if (banner != null) MaintenanceBanner(message = banner.message)
    }
}

@Composable
private fun MaintenanceBanner(message: String) {
    // Lives in the Scaffold's topBar slot, which draws at y=0 with no
    // implicit insets — so we apply statusBars padding ourselves to keep
    // text out from under the notch while the background bleeds up.
    val warning = AppTheme.colors.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(warning.color.copy(alpha = 0.18f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon = Icons.Warning(null),
            color = warning,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = message,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun UpgradeRequiredOverlay(onOpenStore: () -> Unit, onClearOverrides: () -> Unit) {
    // Hero content (icon + title + body) floats centered; the CTA + reassurance
    // anchor to the bottom — a more app-store-y "time to update" treatment than
    // the generic centered BlockingScreen.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background.color)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            UpgradeIconBadge()
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(Res.string.upgrade_required_title),
                typography = AppTheme.typography.Display.D1100.Italic,
                color = AppTheme.colors.accentPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.upgrade_required_body),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DebugEscapeHatch(onClearOverrides = onClearOverrides)
            ButtonPrimary(
                onClick = onOpenStore,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Large,
            ) {
                Text(stringResource(Res.string.upgrade_required_cta))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.upgrade_required_reassurance),
                typography = AppTheme.typography.Caption.C200,
                color = AppTheme.colors.contentTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Gold rounded-square badge with an up-arrow — the "time to update" mark. */
@Composable
private fun UpgradeIconBadge() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.accentPrimary.color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon = Icons.ArrowUp(null),
            size = IconSize.Largest,
            color = AppTheme.colors.onAccentPrimary,
        )
    }
}

@Composable
private fun MaintenanceBlockingOverlay(message: String, onClearOverrides: () -> Unit) {
    BlockingScreen(debugEscape = { DebugEscapeHatch(onClearOverrides = onClearOverrides) }) {
        // TODO: replace this with a watchable animation/illustration so users
        // have something to look at while we're down for maintenance.
        Text(
            text = stringResource(Res.string.upgrade_maintenance_blocking_title),
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DebugEscapeHatch(onClearOverrides: () -> Unit) {
    if (!BuildInfo.isDebug) return
    ButtonDanger(
        onClick = onClearOverrides,
        style = ButtonStyle.Text,
        size = ButtonSize.Small,
    ) {
        Text(stringResource(Res.string.upgrade_debug_clear_overrides))
    }
}

/**
 * Full-screen blocking treatment for guard states. Main [content] is centered;
 * [debugEscape] is tucked at the bottom inside the safe area so QA can break
 * out of an overlay without making it a first-class part of the UI.
 */
@Composable
private fun BlockingScreen(
    debugEscape: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background.color)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
        ) {
            debugEscape()
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
                    color = AppTheme.colors.content,
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
                    color = AppTheme.colors.content,
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

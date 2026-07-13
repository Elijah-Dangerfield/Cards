package com.dangerfield.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.app_offline_explainer_body
import cards.libraries.resources.generated.resources.app_offline_explainer_title
import cards.libraries.resources.generated.resources.app_offline_pill
import cards.libraries.resources.generated.resources.app_status_explainer_dismiss
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.system.LocalAppState
import org.jetbrains.compose.resources.stringResource

/**
 * Top-of-screen status shown whenever [LocalAppState]'s `isOffline` is true.
 * Sits in the Scaffold's `topBar` slot alongside [AppGuardBanner] so the
 * Scaffold owns status-bar inset propagation.
 *
 * A compact tappable [StatusPill] — icon + one word — rather than a full-width
 * strip; tapping opens the explainer dialog so the top of the screen stays clear.
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
internal fun OfflineBannerContent() {
    var explainerOpen by remember { mutableStateOf(false) }
    StatusPill(
        icon = Icons.CloudOff(null),
        label = stringResource(Res.string.app_offline_pill),
        onClick = { explainerOpen = true },
    )
    if (explainerOpen) {
        Dialog(
            title = stringResource(Res.string.app_offline_explainer_title),
            description = stringResource(Res.string.app_offline_explainer_body),
            primaryButtonText = stringResource(Res.string.app_status_explainer_dismiss),
            onDismissRequest = { explainerOpen = false },
            onPrimaryButtonClicked = { explainerOpen = false },
        )
    }
}

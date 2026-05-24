package com.dangerfield.cards.libraries.ui.snackbar

import androidx.compose.runtime.Composable
import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.Duration

/**
 * Fire-and-forget snackbar from anywhere (ViewModel, repository, the
 * router) without a Composable context. The active [SnackbarHost]
 * subscribes to [SnackBarPresenter.requests] and translates each
 * request into a `host.present { Snackbar(...) }` call.
 *
 * For snackbars triggered from compose (button click, navigation
 * event), prefer calling `LocalSnackbarHostState.current?.present { … }`
 * directly — that path lets you pass an arbitrary `@Composable` and
 * keeps the trigger colocated with the event handler that fired it.
 */
fun showSnackBar(
    message: String,
    title: String? = null,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
    withDismissAction: Boolean = true,
    icon: IconResource? = null,
    emoji: String? = null,
    anchor: SnackbarAnchor = SnackbarAnchor.Bottom,
    delayBy: Duration = Duration.ZERO,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    SnackBarPresenter.emit(
        SnackbarRequest(
            title = title,
            message = message,
            actionLabel = actionLabel,
            icon = icon,
            emoji = emoji,
            duration = duration,
            withDismissAction = withDismissAction,
            anchor = anchor,
            delayBy = delayBy,
            onAction = onAction,
            onDismiss = onDismiss,
        )
    )
}

/** Debug-build-only variant that no-ops in release. Handy for dev breadcrumbs. */
fun showDebugSnackBar(
    message: String,
    title: String? = null,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
    withDismissAction: Boolean = true,
    icon: IconResource = Icons.Bug(null),
    anchor: SnackbarAnchor = SnackbarAnchor.Bottom,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
): Boolean {
    if (!BuildInfo.isDebug) return false
    showSnackBar(
        message = message,
        title = title,
        actionLabel = actionLabel,
        duration = duration,
        withDismissAction = withDismissAction,
        icon = icon,
        anchor = anchor,
        onAction = onAction,
        onDismiss = onDismiss,
    )
    return true
}

internal object SnackBarPresenter {
    private val _requests = MutableSharedFlow<SnackbarRequest>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val requests: SharedFlow<SnackbarRequest> = _requests.asSharedFlow()

    fun emit(request: SnackbarRequest) {
        _requests.tryEmit(request)
    }
}

/**
 * Schema for snackbars fired from non-composable contexts (ViewModels,
 * Router). The host translates this into a `present { Snackbar(...) }`
 * call against the active [SnackbarHostState].
 */
internal data class SnackbarRequest(
    val title: String?,
    val message: String,
    val actionLabel: String?,
    val icon: IconResource?,
    val emoji: String?,
    val duration: SnackbarDuration,
    val withDismissAction: Boolean,
    val anchor: SnackbarAnchor,
    val delayBy: Duration,
    val onAction: (() -> Unit)?,
    val onDismiss: (() -> Unit)?,
)

/** Translates a [SnackbarRequest] into the standard opinionated [Snackbar] render. */
@Composable
internal fun SnackbarPresentationScope.PresenterSnackbar(request: SnackbarRequest) {
    Snackbar(
        title = request.title,
        message = request.message,
        icon = request.icon,
        emoji = request.emoji,
        actionLabel = request.actionLabel,
        onAction = request.onAction,
        dismissible = request.withDismissAction,
    )
}

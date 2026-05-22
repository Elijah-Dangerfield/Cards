package com.dangerfield.cards.libraries.ui.snackbar

import androidx.compose.material3.SnackbarResult
import com.dangerfield.cards.libraries.ui.components.PodawanSnackbarVisuals
import com.dangerfield.cards.libraries.ui.components.SnackbarDuration
import com.dangerfield.cards.libraries.ui.components.icon.IconResource
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.random.Random
import com.dangerfield.cards.libraries.core.BuildInfo
import kotlin.time.Duration

fun showSnackBar(
    message: String,
    title: String? = null,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
    withDismissAction: Boolean = true,
    icon: IconResource? = null,
    emoji: String? = null,
    delayBy: Duration = Duration.ZERO,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    SnackBarPresenter.show(
        message = message,
        title = title,
        actionLabel = actionLabel,
        duration = duration,
        withDismissAction = withDismissAction,
        delayBy = delayBy,
        icon = icon,
        emoji = emoji,
        onAction = onAction,
        onDismiss = onDismiss
    )
}

fun showDebugSnackBar(
    message: String,
    title: String? = null,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
    withDismissAction: Boolean = true,
    icon: IconResource = Icons.Bug(null),
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
): Boolean {
    if (!BuildInfo.isDebug) return false
    return SnackBarPresenter.show(
        message = message,
        title = title,
        actionLabel = actionLabel,
        duration = duration,
        withDismissAction = withDismissAction,
        icon = icon,
        onAction = onAction,
        onDismiss = onDismiss
    )
}

fun showSnackBar(
    message: SnackbarMessage
) {
    SnackBarPresenter.show(message)
}

/**
 * Global presenter that allows any layer to enqueue snackbars without owning a host.
 * The App composable owns the host and collects [requests] to render them.
 */
object SnackBarPresenter {

    private val callbacks = mutableMapOf<Long, SnackbarCallbacks>()
    private val _requests = MutableSharedFlow<SnackbarMessage>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val requests: SharedFlow<SnackbarMessage> = _requests.asSharedFlow()

    fun show(message: SnackbarMessage): Boolean {
        callbacks[message.id] = SnackbarCallbacks(message.onAction, message.onDismiss)
        val emitted = _requests.tryEmit(message)
        if (!emitted) {
            callbacks.remove(message.id)
        }
        return emitted
    }

    fun show(
        message: String,
        title: String? = null,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Short,
        withDismissAction: Boolean = true,
        delayBy: Duration = Duration.ZERO,
        icon: IconResource? = null,
        emoji: String? = null,
        onAction: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
    ): Boolean {
        return show(
            SnackbarMessage(
                title = title,
                message = message,
                actionLabel = actionLabel,
                duration = duration,
                withDismissAction = withDismissAction,
                delayBy = delayBy,
                icon = icon,
                emoji = emoji,
                onAction = onAction,
                onDismiss = onDismiss
            )
        )
    }

    fun onResult(messageId: Long, result: SnackbarResult) {
        val callback = callbacks.remove(messageId)
        when (result) {
            SnackbarResult.ActionPerformed -> callback?.onAction?.invoke()
            SnackbarResult.Dismissed -> callback?.onDismiss?.invoke()
        }
    }

    private data class SnackbarCallbacks(
        val onAction: (() -> Unit)?,
        val onDismiss: (() -> Unit)?,
    )
}

/**
 * Representation of a snackbar to be presented through [SnackBarPresenter].
 */
data class SnackbarMessage(
    val title: String? = null,
    val message: String,
    val actionLabel: String? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val withDismissAction: Boolean = true,
    val delayBy: Duration = Duration.ZERO,
    val icon: IconResource? = null,
    val emoji: String? = null,
    val id: Long = Random.nextLong(),
    val onAction: (() -> Unit)? = null,
    val onDismiss: (() -> Unit)? = null,
)

fun SnackbarMessage.toVisuals(): PodawanSnackbarVisuals = PodawanSnackbarVisuals(
    id = id,
    title = title,
    icon = icon,
    emoji = emoji,
    message = message,
    actionLabel = actionLabel,
    withDismissAction = withDismissAction,
    duration = duration.toMaterial()
)

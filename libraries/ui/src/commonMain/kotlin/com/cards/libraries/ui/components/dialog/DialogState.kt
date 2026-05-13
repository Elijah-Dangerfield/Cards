package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Controls dialog visibility so callers can request animated show/dismiss operations. */
@Stable
class DialogState internal constructor(initiallyVisible: Boolean) {
    internal var targetVisible by mutableStateOf(initiallyVisible)
        private set

    val isVisible: Boolean get() = targetVisible

    fun show() {
        targetVisible = true
    }

    fun dismiss() {
        targetVisible = false
    }

    companion object {
        val Saver: Saver<DialogState, Boolean> = Saver(
            save = { it.targetVisible },
            restore = { DialogState(it) }
        )
    }
}

/** Remembers a [DialogState] that survives configuration changes via saveable state. */
@Composable
fun rememberDialogState(initiallyVisible: Boolean = true): DialogState {
    return rememberSaveable(saver = DialogState.Saver) {
        DialogState(initiallyVisible)
    }
}

/** Keyed variant used by navigation destinations so each entry tracks its own dialog state. */
@Composable
fun rememberDestinationDialogState(
    destinationId: String,
    initiallyVisible: Boolean = true
): DialogState {
    return rememberSaveable(destinationId, saver = DialogState.Saver) {
        DialogState(initiallyVisible)
    }
}

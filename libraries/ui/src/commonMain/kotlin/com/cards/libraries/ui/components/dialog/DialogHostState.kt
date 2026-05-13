package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** CompositionLocal that exposes the ambient [DialogHostState] for hosted dialogs. */
val LocalDialogHostState = staticCompositionLocalOf<DialogHostState?> { null }

@Composable
fun rememberDialogHostState(): DialogHostState = remember { DialogHostState() }

/** Holds the collection of currently hosted dialogs so they can be drawn at the top level. */
@Stable
class DialogHostState internal constructor() {

    private val _entries = MutableStateFlow<List<DialogHostEntry>>(emptyList())

    internal val entries: StateFlow<List<DialogHostEntry>> = _entries.asStateFlow()

    internal fun upsert(entry: DialogHostEntry) {
        _entries.update { current ->
            val index = current.indexOfFirst { it.id == entry.id }
            if (index == -1) {
                current + entry
            } else {
                current.toMutableList().apply { this[index] = entry }
            }
        }
    }

    internal fun remove(id: Long) {
        _entries.update { current -> current.filterNot { it.id == id } }
    }
}

/** Snapshot of a dialog registered with [DialogHostState]. */
@Immutable
internal data class DialogHostEntry(
    val id: Long,
    val visible: Boolean,
    val modifier: Modifier,
    val properties: ModalDialogProperties,
    val animationSpec: ModalDialogAnimationSpec,
    val scrimColor: Color,
    val contentAlignment: Alignment,
    val requestDismiss: () -> Unit,
    val onDismissed: () -> Unit,
    val content: @Composable BoxScope.() -> Unit,
)

@Composable
fun DialogHost(
    modifier: Modifier = Modifier,
    hostState: DialogHostState? = LocalDialogHostState.current,
) {
    if (hostState == null) return
    val entries by hostState.entries.collectAsState()

    if (entries.isEmpty()) {
        return
    }

    Box(modifier = modifier) {
        entries.forEach { entry ->
            val onDismissed = remember(entry.id, entry.onDismissed) {
                {
                    hostState.remove(entry.id)
                    entry.onDismissed()
                }
            }
            DialogOverlay(
                visible = entry.visible,
                onDismissRequest = entry.requestDismiss,
                onDismissComplete = onDismissed,
                modifier = entry.modifier,
                properties = entry.properties,
                animationSpec = entry.animationSpec,
                scrimColor = entry.scrimColor,
                contentAlignment = entry.contentAlignment,
                content = entry.content
            )
        }
    }
}

package com.dangerfield.cards.libraries.ui.components.dialog

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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

/**
 * Draws every hosted dialog inside a top-most [Popup] so a hosted dialog
 * always sits above Material's [androidx.compose.material3.ModalBottomSheet],
 * which renders in its own platform window and would otherwise occlude a
 * dialog drawn inline in the main composition. Only the dialog's own scrim +
 * dismissal (owned by [DialogOverlay]) drive interaction, so the popup itself
 * doesn't intercept back/outside-tap — [DialogOverlay]'s [BackHandler] handles
 * back, and it needs the popup to be focusable for that to fire.
 *
 * When there are no entries the popup is not shown at all, so a fresh dialog
 * registered while a sheet is up creates its window *after* the sheet's — and
 * therefore on top of it.
 */
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

    val hostedEntries: @Composable () -> Unit = {
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

    // Compose Popups don't render in the @Preview inspection surface, so fall
    // back to inline rendering there — otherwise every dialog preview goes
    // blank. At runtime the popup is what lifts the dialog above bottom sheets.
    if (LocalInspectionMode.current) {
        Box(modifier = modifier) { hostedEntries() }
    } else {
        Popup(
            // Full-screen, edge-to-edge popup. On skiko platforms (iOS/desktop)
            // this must opt out of platform-inset padding, or the popup is inset by
            // the safe area and the dialog's scrim stops short of the screen edges,
            // reading as a floating panel rather than a full-screen dim (CARDS-78).
            // `usePlatformInsets` lives only on the skiko `PopupProperties`, not
            // Android's, so the value is supplied per-platform via [fullScreenDialogPopupProperties].
            properties = fullScreenDialogPopupProperties(),
        ) {
            Box(modifier = Modifier.fillMaxSize().then(modifier)) { hostedEntries() }
        }
    }
}

/**
 * [PopupProperties] for the top-most dialog host popup: focusable (so
 * [DialogOverlay]'s BackHandler fires), non-self-dismissing, non-clipping, and
 * full-screen. The full-screen part is platform-specific — skiko targets must
 * pass `usePlatformInsets = false` to reach under the safe area, a parameter that
 * doesn't exist on Android's [PopupProperties] (Android popups already fill the
 * window).
 */
internal expect fun fullScreenDialogPopupProperties(): PopupProperties

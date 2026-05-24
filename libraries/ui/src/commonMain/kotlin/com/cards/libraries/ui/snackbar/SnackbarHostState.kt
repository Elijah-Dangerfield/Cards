package com.dangerfield.cards.libraries.ui.snackbar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Anchor edge where a snackbar is rendered. Bottom is the conventional
 * default and is what 95% of toasts should use; Top exists for cases
 * where the bottom edge is occupied by load-bearing UI (active hand,
 * full-bleed bottom sheet) or where the message contextually belongs
 * near the top (system status, sticky banner).
 */
enum class SnackbarAnchor { Top, Bottom }

/**
 * How long the host shows a snackbar before auto-dismissing. The host
 * applies the OS a11y multiplier on top of these.
 */
enum class SnackbarDuration {
    Short, Long, Indefinite;

    fun toDuration(): Duration = when (this) {
        Short -> 4_000.milliseconds
        Long -> 10_000.milliseconds
        Indefinite -> Duration.INFINITE
    }
}

/** CompositionLocal exposing the ambient host state. App.kt mounts one and provides it here. */
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }

@Composable
fun rememberSnackbarHostState(): SnackbarHostState = remember { SnackbarHostState() }

/**
 * Holds the queue of snackbars to be rendered by [SnackbarHost].
 *
 * Queue policy is one-at-a-time: only the head entry is visible;
 * subsequent presentations wait until it dismisses. Both compose-side
 * callers (`host.present { Snackbar(...) }`) and the global
 * [showSnackBar] (for ViewModels) flow through the same queue so
 * ordering is predictable regardless of trigger source.
 */
@Stable
class SnackbarHostState internal constructor() {

    private val _entries = MutableStateFlow<List<SnackbarHostEntry>>(emptyList())
    internal val entries: StateFlow<List<SnackbarHostEntry>> = _entries.asStateFlow()

    /**
     * Enqueues a snackbar for presentation. The [content] lambda runs
     * inside a [SnackbarPresentationScope], which gives it a `dismiss`
     * callback wired to this entry — the typical caller writes
     * `host.present { Snackbar(message = "Saved!") }` and the
     * snackbar's dismiss button + action route through that callback.
     *
     * Returns the entry id so an indefinite snackbar can be dismissed
     * later via [dismiss]; for time-bounded snackbars the id is
     * usually discardable.
     */
    fun present(
        anchor: SnackbarAnchor = SnackbarAnchor.Bottom,
        duration: SnackbarDuration = SnackbarDuration.Short,
        dismissible: Boolean = true,
        onDismiss: (() -> Unit)? = null,
        content: @Composable SnackbarPresentationScope.() -> Unit,
    ): Long {
        val id = Random.nextLong()
        val requestDismiss: () -> Unit = lambda@{
            val current = _entries.value.firstOrNull { it.id == id } ?: return@lambda
            if (!current.visible) return@lambda
            upsert(current.copy(visible = false))
        }
        val scope = SnackbarPresentationScope(dismiss = requestDismiss)
        upsert(
            SnackbarHostEntry(
                id = id,
                anchor = anchor,
                duration = duration,
                dismissible = dismissible,
                visible = true,
                requestDismiss = requestDismiss,
                onDismissed = { onDismiss?.invoke() },
                content = { scope.content() },
            )
        )
        return id
    }

    /** Force-dismiss the entry with the given id. No-op if it's already gone or animating out. */
    fun dismiss(id: Long) {
        val current = _entries.value.firstOrNull { it.id == id } ?: return
        if (!current.visible) return
        upsert(current.copy(visible = false))
    }

    internal fun upsert(entry: SnackbarHostEntry) {
        _entries.update { current ->
            val index = current.indexOfFirst { it.id == entry.id }
            if (index == -1) current + entry
            else current.toMutableList().apply { this[index] = entry }
        }
    }

    internal fun remove(id: Long) {
        _entries.update { current -> current.filterNot { it.id == id } }
    }
}

/**
 * Receiver scope for [SnackbarHostState.present] lambdas. Calling a
 * `Snackbar(...)` outside this scope is a compile error — that's the
 * point: the typed scope keeps snackbar rendering tied to the host
 * that's actually going to display it, so [dismiss] always refers to
 * "this presentation" without the caller having to plumb a state
 * reference.
 */
@Stable
class SnackbarPresentationScope internal constructor(
    /** Fold this snackbar away early. Safe to call multiple times. */
    val dismiss: () -> Unit,
)

/**
 * Snapshot of a snackbar registered with [SnackbarHostState].
 *
 * `visible` is the upstream intent — true means "the source still
 * wants me on screen", false means "animate out". The host owns the
 * actual animated visibility transition; this flag is what drives it.
 */
@Immutable
internal data class SnackbarHostEntry(
    val id: Long,
    val anchor: SnackbarAnchor,
    val duration: SnackbarDuration,
    val dismissible: Boolean,
    val visible: Boolean,
    /** Called when the host (timeout, swipe, dismiss button) wants the entry gone. */
    val requestDismiss: () -> Unit,
    /** Called once the exit animation finishes. */
    val onDismissed: () -> Unit,
    val content: @Composable () -> Unit,
)

package com.dangerfield.cards.libraries.navigation.floatingwindow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.LocalOwnersProvider

/**
 * Host used to show bottom sheets and dialogs that are added to the back stack.
 * This is what allows for floating sheets to be their own destinations in a nav
 * graph rather than being shown by the parent composable.
 *
 * Shows each [Destination] on the [FloatingWindowNavigator]'s back stack.
 *
 * Note that [PodawanApp] should be the only caller of this function.
 */
@Composable
fun FloatingWindowHost(floatingWindowNavigator: FloatingWindowNavigator) {
    val saveableStateHolder = rememberSaveableStateHolder()
    val backstackState by floatingWindowNavigator.backStack.collectAsState()
    val visibleBackStack = rememberVisibleList(backstackState)
    visibleBackStack.PopulateVisibleList(backstackState)

    visibleBackStack.forEach { backStackEntry ->

        val destination = backStackEntry.destination as FloatingWindowNavigator.Destination

        DisposableEffect(backStackEntry) {
            onDispose {
                floatingWindowNavigator.onTransitionComplete(backStackEntry)
            }
        }

        backStackEntry.LocalOwnersProvider(saveableStateHolder) {
            destination.content(backStackEntry)
        }
    }
}

/**
 * @param transitionsInProgress are the current back stack entries (visible or not)
 *
 * Sets a lifecycle observer to add and remove entries to the visible state based on lifecycle state
 */
@Composable
internal fun MutableList<NavBackStackEntry>.PopulateVisibleList(
    transitionsInProgress: Collection<NavBackStackEntry>
) {
    val isInspecting = LocalInspectionMode.current
    transitionsInProgress.forEach { entry ->
        DisposableEffect(entry.lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                // for every lifecycle event, make sure the dialog is visible in preview
                if (isInspecting && !contains(entry)) {
                    add(entry)
                }
                // ON_START -> add to visibleBackStack, ON_STOP -> remove from visibleBackStack
                if (event == Lifecycle.Event.ON_START) {
                    // We want to treat the visible lists as Sets but we want to keep
                    // the functionality of mutableStateListOf() so that we recompose in response
                    // to adds and removes.
                    if (!contains(entry)) {
                        add(entry)
                    }
                }
                if (event == Lifecycle.Event.ON_STOP) {
                    remove(entry)
                }
            }
            entry.lifecycle.addObserver(observer)
            onDispose {
                entry.lifecycle.removeObserver(observer)
            }
        }
    }
}

/**
 * Takes in the state of the back stack and returns a list of the entries that are visible
 * (i.e. have a lifecycle state of at least STARTED).
 *
 * For preview, all entries are considered visible.
 */
@Composable
internal fun rememberVisibleList(
    transitionsInProgress: Collection<NavBackStackEntry>
): SnapshotStateList<NavBackStackEntry> {
    // show dialog in preview
    val isInspecting = LocalInspectionMode.current
    return remember(transitionsInProgress) {
        mutableStateListOf<NavBackStackEntry>().also {
            it.addAll(
                transitionsInProgress.filter { entry ->
                    if (isInspecting) {
                        true
                    } else {
                        entry.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    }
                }
            )
        }
    }
}

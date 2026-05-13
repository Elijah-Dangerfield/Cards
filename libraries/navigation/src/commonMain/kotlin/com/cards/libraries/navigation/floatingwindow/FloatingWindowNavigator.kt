package com.dangerfield.cards.libraries.navigation.floatingwindow

import androidx.compose.runtime.Composable
import androidx.navigation.FloatingWindow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavOptions
import androidx.navigation.Navigator

/**
 * Navigator used to allow the nav graph to show floating windows in response to a navigation.
 */
@Navigator.Name("floatingwindow")
class FloatingWindowNavigator : Navigator<FloatingWindowNavigator.Destination>() {

    /**
     * Get the back stack from the [state].
     */
    internal val backStack get() = state.backStack

    override fun navigate(
        entries: List<NavBackStackEntry>,
        navOptions: NavOptions?,
        navigatorExtras: Extras?
    ) {
        entries.forEach { entry ->
            state.pushWithTransition(entry)
        }
    }

    override fun createDestination(): Destination {
        return Destination(this) {  }
    }

    override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) {
        state.popWithTransition(popUpTo, savedState)
        // When popping, the incoming dialog is marked transitioning to hold it in
        // STARTED. With pop complete, we can remove it from transition so it can move to RESUMED.
        val popIndex = state.transitionsInProgress.value.indexOf(popUpTo)
        // do not mark complete for entries up to and including popUpTo
        state.transitionsInProgress.value.forEachIndexed { index, entry ->
            if (index > popIndex) onTransitionComplete(entry)
        }
    }

    internal fun onTransitionComplete(entry: NavBackStackEntry) {
        state.markTransitionComplete(entry)
    }

    /**
     * NavDestination specific to [FloatingWindowNavigator]
     */
    @NavDestination.ClassType(Composable::class)
    class Destination(
        navigator: FloatingWindowNavigator,
        internal val content: @Composable (NavBackStackEntry) -> Unit
    ) : NavDestination(navigator), FloatingWindow
}

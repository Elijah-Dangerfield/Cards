package com.dangerfield.cards

import androidx.lifecycle.ViewModel
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-level ViewModel. Holds the start destination and an `isReady` signal for
 * Android's splash-screen API.
 *
 * Scoped as singleton so Android's splash-screen API can read the same instance
 * used by the App composable.
 *
 * The iOS splash-overlay state lives inside the [App] composable itself, not
 * here — keeping it out of the reactive app state means flipping it cannot
 * trigger a root recomposition (which previously caused the NavHost to re-emit
 * its start destination).
 */
@SingleIn(AppScope::class)
@Inject
class AppViewModel(
    private val appCache: AppCache,
) : ViewModel() {

    // If you have onboarding, auth, or other gating before home, change this.
    val startDestination: Route = HomeRoute()

    private val _isReady = MutableStateFlow(true)

    /**
     * Exposed to Android's splash screen API for keepOnScreenCondition.
     * True once we've determined where to navigate. We compute this synchronously
     * so there is no "pending" state.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
}

package com.dangerfield.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-level ViewModel. Resolves the start destination asynchronously by
 * reading the persistent `AppData` cache — onboarded users land on
 * [HomeRoute]; first-launch users land on [OnboardingRoute].
 *
 * Scoped as singleton so Android's splash-screen API can read the same
 * instance used by the App composable.
 *
 * `startDestination` is a [StateFlow] that's `null` until the cache read
 * completes. The App composable blocks NavHost construction on a non-null
 * value, keeping the splash visible during the brief async read.
 *
 * Why not store the destination synchronously: `AppCache.get()` is suspend
 * because DataStore is async on Android. Using `runBlocking` here would
 * delay process startup; reading reactively lets the splash own the
 * waiting state.
 */
@SingleIn(AppScope::class)
@Inject
class AppViewModel(
    private val appCache: AppCache,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<Route?>(null)
    val startDestination: StateFlow<Route?> = _startDestination.asStateFlow()

    private val _isReady = MutableStateFlow(false)

    /**
     * True once we've determined where to navigate. Used by Android's
     * splash-screen API for `keepOnScreenCondition`.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        viewModelScope.launch {
            val data = appCache.get()
            _startDestination.value = if (data.hasUserOnboarded) HomeRoute() else OnboardingRoute()
            _isReady.value = true
        }
    }
}

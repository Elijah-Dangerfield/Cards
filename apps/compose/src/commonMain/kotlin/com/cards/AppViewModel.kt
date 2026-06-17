package com.dangerfield.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
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
 * `isReady` also waits on the first [ProfileRepository.observe] emission
 * for returning users — that's the moment the profile resolution
 * (server `/v1/me`, falling back to cache when offline) completes. Holding
 * the splash through this window means the first frame the user sees is
 * authoritative state, not a stale flash. First-launch users skip this
 * wait — they haven't onboarded yet, no profile to resolve.
 */
@SingleIn(AppScope::class)
@Inject
class AppViewModel(
    private val appCache: AppCache,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _startDestination = MutableStateFlow<Route?>(null)
    val startDestination: StateFlow<Route?> = _startDestination.asStateFlow()

    /**
     * Fires when the auth server rejected our session mid-run
     * ([AuthState.Unauthenticated.Reason.SessionExpired]). The App composable
     * collects this to boot the user back to the landing + explain why. A
     * one-shot **event** (navigation), so a `Channel` — same convention as
     * `SEAViewModel`: exactly-once delivery to the single collector, no replay,
     * buffered if it fires before the collector is ready.
     */
    private val _sessionExpired = Channel<SessionExpired>(Channel.UNLIMITED)
    val sessionExpired: Flow<SessionExpired> = _sessionExpired.receiveAsFlow()

    private val _isReady = MutableStateFlow(false)

    /**
     * True once we've determined where to navigate AND (for returning
     * users) profile has resolved. Drives Android's splash-screen API
     * via `keepOnScreenCondition`.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        viewModelScope.launch {
            val data = appCache.get()
            val onboarded = data.hasUserOnboarded
            _startDestination.value = if (onboarded) HomeRoute() else OnboardingRoute()

            if (onboarded) {
                // Hold the splash until profile resolves so home renders
                // with authoritative data on the first frame instead of
                // flashing stale cache values.
                profileRepository.observe().first()
            }
            _isReady.value = true
        }

        // Boot the user to re-auth when the auth server rejects our session
        // mid-run. The teardown (clear caches / session / account-scoped data)
        // already happened in AuthRepository via the UserChanged(prev→null)
        // path — this only handles the *navigation* + the "why" message, which
        // an ambient network-triggered event can't do from a feature screen.
        viewModelScope.launch {
            var previousExpired = false
            authRepository.observe().collect { auth ->
                val expired = auth is AuthState.Unauthenticated &&
                    auth.reason == AuthState.Unauthenticated.Reason.SessionExpired
                // Fire only on the edge into SessionExpired, never repeatedly.
                if (expired && !previousExpired) {
                    // Mirror sign-out so a cold boot also lands on onboarding.
                    appCache.update { it.copy(hasUserOnboarded = false) }
                    _sessionExpired.trySend(
                        SessionExpired(wasAnonymous = (auth as AuthState.Unauthenticated).wasAnonymous),
                    )
                }
                previousExpired = expired
            }
        }
    }

    /** A server-rejected session; [wasAnonymous] picks the guest vs claimed copy. */
    data class SessionExpired(val wasAnonymous: Boolean)
}

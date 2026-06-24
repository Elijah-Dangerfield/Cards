package com.dangerfield.cards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.onboarding.OnboardingRoute
import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.config.EnsureAppConfigLoaded
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hard cap on how long the Compose boot gate waits for the unauthed
 * app-config to resolve. The config repository already times its network
 * refresh out at 5s and falls back to bundled defaults, so this is just a
 * backstop so a wedged fetch can never strand the user on the loading
 * screen.
 */
private const val BootConfigTimeoutMillis = 8_000L

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
 * Two readiness signals drive a two-stage boot:
 *  - [isReady] flips as soon as the start destination is resolved. It
 *    releases the platform splash (Android's native splash API / iOS's
 *    splash overlay) so it hands off to the Compose boot gate.
 *  - [isBootComplete] flips once the remaining boot work finishes: the
 *    unauthed app-config has resolved (or timed out) and — for returning
 *    users — the first [ProfileRepository.observe] emission has landed.
 *    The Compose boot gate shows the cycling loading caption until this is
 *    true, then renders the real nav graph.
 *
 * Holding the gate on app-config means onboarding's first frame renders the
 * real starter-grant / suggested-name (both ride the unauthed config tree)
 * instead of the "server hasn't told us yet" sentinel; holding it on the
 * profile resolve means a returning user's home renders authoritative state
 * on frame one, not a stale cache flash.
 */
@SingleIn(AppScope::class)
@Inject
class AppViewModel(
    private val appCache: AppCache,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val ensureAppConfigLoaded: EnsureAppConfigLoaded,
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
     * True once the start destination is resolved. Drives the platform
     * splash (Android's splash-screen API via `keepOnScreenCondition`) so it
     * dismisses promptly and hands off to the Compose boot gate.
     */
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isBootComplete = MutableStateFlow(false)

    /**
     * True once the boot gate's remaining work has finished — app-config
     * resolved (or timed out) and, for returning users, the first profile
     * emission landed. The App composable holds the cycling boot-loading
     * screen until this flips, then renders the nav graph.
     */
    val isBootComplete: StateFlow<Boolean> = _isBootComplete.asStateFlow()

    init {
        viewModelScope.launch {
            val data = appCache.get()
            val onboarded = data.hasUserOnboarded
            _startDestination.value = if (onboarded) HomeRoute() else OnboardingRoute()
            // Start destination resolved — release the platform splash; the
            // Compose boot gate now covers the rest of the wait.
            _isReady.value = true

            coroutineScope {
                // Hold the boot gate until the unauthed app-config resolves so
                // onboarding's starter-grant / suggested-name render real
                // values on frame one. Capped so a wedged fetch can't strand
                // the user — the config repo falls back to bundled defaults.
                val config = launch {
                    withTimeoutOrNull(BootConfigTimeoutMillis) { ensureAppConfigLoaded() }
                }
                if (onboarded) {
                    // Returning users: also wait for the profile resolve so
                    // home renders authoritative data on the first frame
                    // instead of flashing stale cache values.
                    profileRepository.observe().first()
                }
                config.join()
            }
            _isBootComplete.value = true
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
                    // Route to the blocking session-expired screen; that screen's
                    // Retry recovers in place (and restores hasUserOnboarded), while
                    // its Logout clears the flag and lands on onboarding.
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

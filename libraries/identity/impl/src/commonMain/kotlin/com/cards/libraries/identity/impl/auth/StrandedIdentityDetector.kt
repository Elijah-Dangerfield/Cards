package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.profile.Profile
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Canary for the stranding bug. On foreground / connectivity-regained, if the
 * profile is still [Profile.Fallback] while the user has onboarded and is
 * online, something is wrong — that user should have a real session by now. It
 * warns (which lands as a Sentry breadcrumb via the log tree, tagged
 * [STRANDED_TOKEN]) so the condition is observable in production.
 *
 * Post-[GuestSessionHealer] this should read **zero** — if it ever fires, the
 * heal path isn't recovering someone it should. Deliberately observe-only: the
 * healer owns recovery; this just measures it.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventListener::class, multibinding = true)
@Inject
class StrandedIdentityDetector(
    // Lazy providers break the DI cycle (AppEventListener → ProfileRepository →
    // AuthRepository → AppEventBus → dispatcher → this set).
    profileRepositoryProvider: () -> ProfileRepository,
    authRepositoryProvider: () -> AuthRepository,
    private val appCache: AppCache,
    private val appState: AppState,
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    private val logger = KLog.withTag("StrandedIdentity")
    private val profileRepository: ProfileRepository by lazy { profileRepositoryProvider() }
    private val authRepository: AuthRepository by lazy { authRepositoryProvider() }

    override fun onForeground(event: AppEvent.OnForeground) = check("foreground")
    override fun onConnectivityRegained(event: AppEvent.ConnectivityRegained) = check("connectivityRegained")

    private fun check(source: String) {
        appScope.launch {
            Catching { detect(source) }
                .logOnFailure { "Stranded-identity detection failed (source=$source)" }
        }
    }

    private suspend fun detect(source: String) {
        val profile = Catching { profileRepository.current() }.getOrNull() ?: return
        if (profile !is Profile.Fallback) return
        val onboarded = Catching { appCache.get().hasUserOnboarded }.getOrNull() ?: false
        if (!onboarded) return
        if (appState.isOffline.value) return
        // A declared-dead session (server rejection or AUTH-19 unrecoverable)
        // is the healer *deliberately* not minting while the user sits on the
        // recovery screen — not a heal failure. Don't cry wolf over it.
        val auth = Catching { authRepository.current() }.getOrNull()
        if (auth is AuthState.Unauthenticated &&
            auth.reason != AuthState.Unauthenticated.Reason.None
        ) return
        logger.w {
            "$STRANDED_TOKEN source=$source — onboarded + online but still Profile.Fallback(${profile.id}); " +
                "identity self-heal should have recovered this"
        }
    }

    private companion object {
        /** Stable, greppable breadcrumb token. Should read zero post-heal. */
        const val STRANDED_TOKEN: String = "stranded_fallback_online_onboarded"
    }
}

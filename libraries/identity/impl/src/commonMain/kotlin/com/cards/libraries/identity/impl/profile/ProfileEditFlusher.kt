package com.dangerfield.cards.libraries.identity.impl.profile

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.profile.ProfileRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Drives [ProfileRepository.flushPendingEdits] on warm foreground +
 * connectivity-regained. Most queued offline edits flush automatically when auth
 * flips back to Authenticated (the profile repo re-resolves on the auth change);
 * this covers the case where the session **stayed** valid but a PATCH failed
 * transiently — auth never re-emits, so without this trigger the edit would sit
 * queued until the next sign-in.
 *
 * Lazy `() -> ProfileRepository` provider breaks the DI construction cycle:
 * this is in the `AppEventListener` set, and `ProfileRepository → AuthRepository
 * → AppEventBus → dispatcher → that set`. Mirrors `GuestSessionHealer` /
 * `InAppMessageManagerImpl`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventListener::class, multibinding = true)
@Inject
class ProfileEditFlusher(
    profileRepositoryProvider: () -> ProfileRepository,
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    private val logger = KLog.withTag("ProfileEditFlusher")
    private val profileRepository: ProfileRepository by lazy { profileRepositoryProvider() }

    override fun onForeground(event: AppEvent.OnForeground) {
        if (event.isColdBoot) return
        flush("foreground")
    }

    override fun onConnectivityRegained(event: AppEvent.ConnectivityRegained) = flush("connectivityRegained")

    private fun flush(source: String) {
        appScope.launch {
            Catching { profileRepository.flushPendingEdits() }
                .logOnFailure { "Profile edit flush failed (source=$source)" }
        }
    }
}

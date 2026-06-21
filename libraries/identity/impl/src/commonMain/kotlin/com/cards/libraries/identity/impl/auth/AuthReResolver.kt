package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppEvent
import com.dangerfield.cards.libraries.cards.AppEventListener
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Re-resolves an *existing* session on warm foreground + connectivity-regained,
 * when online. A claimed user's session can go dormant or near-expiry while
 * backgrounded/offline; [AuthRepository.retry] refreshes it (no-op if already
 * Authenticated) so the next authed call rides a live token instead of failing.
 *
 * **Division of labor:** this only ever *re-resolves* — it never mints. Minting a
 * brand-new session for a genuinely session-less (stranded) user is
 * [GuestSessionHealer]'s job. Keeping the two split means a warm resume can
 * cheaply refresh a real account without risking an accidental guest mint, and
 * the healer's mint decision stays the single gated place that creates accounts.
 *
 * Skips cold-boot foreground (the initial bootstrap resolve already runs then).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppEventListener::class, multibinding = true)
@Inject
class AuthReResolver(
    // Lazy provider breaks the DI cycle (AppEventListener → AuthRepository →
    // AppEventBus → dispatcher → this set). Mirrors InAppMessageManagerImpl.
    authRepositoryProvider: () -> AuthRepository,
    private val appState: AppState,
    private val appScope: AppCoroutineScope,
) : AppEventListener {

    private val logger = KLog.withTag("AuthReResolver")
    private val authRepository: AuthRepository by lazy { authRepositoryProvider() }

    override fun onForeground(event: AppEvent.OnForeground) {
        if (event.isColdBoot) return
        reResolve("foreground")
    }

    override fun onConnectivityRegained(event: AppEvent.ConnectivityRegained) = reResolve("connectivityRegained")

    private fun reResolve(source: String) {
        if (appState.isOffline.value) {
            logger.d { "re-resolve skipped — offline (source=$source)" }
            return
        }
        appScope.launch {
            Catching {
                logger.d { "re-resolving session (source=$source)" }
                authRepository.retry()
            }.logOnFailure { "Auth re-resolve failed (source=$source)" }
        }
    }
}

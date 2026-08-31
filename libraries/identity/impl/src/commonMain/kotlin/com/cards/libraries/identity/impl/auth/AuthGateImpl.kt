package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.cards.AppCache
import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.AuthGate
import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The single producer of auth-readiness (see [AuthGate]). An [AutoInit] so it
 * starts caching at app launch, before any gated navigation or authed call can
 * consult it. Fail-closed: until auth resolves (cached null) an `Account`
 * verdict is Blocked — correct for navigation, where gated routes are only
 * reachable well after resolve; launch-time callers go through [awaitVerdict].
 *
 * A stranded user (onboarded + online + genuinely session-less) gets
 * [AuthReason.FinishingSetup] — honest *now* — and a fire-and-forget
 * [GuestSessionHealer.heal] kick; the user's retry succeeds once the heal
 * lands. The kick never happens for unresolved, signed-out, expired, offline,
 * or pre-onboarding states.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthGate::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class AuthGateImpl(
    authRepository: AuthRepository,
    guestAccountCreator: GuestAccountCreator,
    appCache: AppCache,
    private val appState: AppState,
    private val healer: GuestSessionHealer,
    appScope: AppCoroutineScope,
) : AuthGate, AutoInit {

    private val authState = MutableStateFlow<AuthState?>(null)
    private val creationState = MutableStateFlow<AccountCreationState>(AccountCreationState.Idle)
    private val hasOnboarded = MutableStateFlow(false)

    init {
        appScope.launch {
            Catching { authRepository.observe().collect { authState.value = it } }
                .logOnFailure { "Auth-gate auth observation failed" }
        }
        appScope.launch {
            Catching { guestAccountCreator.state.collect { creationState.value = it } }
                .logOnFailure { "Auth-gate creation observation failed" }
        }
        appScope.launch {
            Catching {
                hasOnboarded.value = appCache.get().hasUserOnboarded
                appCache.updates.collect { hasOnboarded.value = it.hasUserOnboarded }
            }.logOnFailure { "Auth-gate onboarding observation failed" }
        }
    }

    override fun verdict(requirement: AuthRequirement): AuthVerdict {
        if (requirement == AuthRequirement.None) return AuthVerdict.Ready
        return when (val auth = authState.value) {
            is AuthState.Authenticated ->
                if (requirement == AuthRequirement.ClaimedAccount && auth.isAnonymous) {
                    AuthVerdict.Blocked(AuthReason.NeedClaimedAccount)
                } else {
                    // Even offline: we hold a (possibly stale) session, so the
                    // request proceeds and fails at transport like any ordinary
                    // network error — never through the gate.
                    AuthVerdict.Ready
                }
            else -> AuthVerdict.Blocked(blockedReason(auth as? AuthState.Unauthenticated))
        }
    }

    override suspend fun awaitVerdict(requirement: AuthRequirement): AuthVerdict {
        if (requirement == AuthRequirement.None) return AuthVerdict.Ready
        authState.first { it != null }
        return verdict(requirement)
    }

    private fun blockedReason(unauth: AuthState.Unauthenticated?): AuthReason {
        val creation = creationState.value
        val creationDegraded =
            creation is AccountCreationState.InProgress || creation is AccountCreationState.Failed
        return when {
            creationDegraded -> AuthReason.FinishingSetup
            healable(unauth) -> {
                healer.heal("authGate")
                AuthReason.FinishingSetup
            }
            // Backend unreachable — the session couldn't be verified. Route to the
            // dismissible offline surface (enter the app behind the banner), not a
            // NeedAccount wall or a SessionExpired recovery screen. Keyed off the
            // reason so it holds even when the optimistic isOffline flag hasn't
            // resolved yet (AUTH-30).
            unauth?.reason == AuthState.Unauthenticated.Reason.Unreachable -> AuthReason.Offline
            appState.isOffline.value -> AuthReason.Offline
            unauth?.reason == AuthState.Unauthenticated.Reason.SessionExpired -> AuthReason.SessionExpired
            else -> AuthReason.NeedAccount
        }
    }

    // Stranded: onboarded, online, and genuinely session-less — resolved to
    // Unauthenticated with no deliberate sign-out and no server-killed session.
    // Unresolved (null) auth is never healable: fail closed, don't mint.
    private fun healable(unauth: AuthState.Unauthenticated?): Boolean =
        unauth != null &&
            unauth.reason == AuthState.Unauthenticated.Reason.None &&
            hasOnboarded.value &&
            !appState.isOffline.value
}

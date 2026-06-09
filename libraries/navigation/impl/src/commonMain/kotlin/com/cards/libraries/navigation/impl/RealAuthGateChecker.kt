package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.AutoInit
import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.identity.auth.AccountCreationState
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.identity.auth.GuestAccountCreator
import com.dangerfield.cards.libraries.navigation.AuthGateChecker
import com.dangerfield.cards.libraries.navigation.AuthGateRoute
import com.dangerfield.cards.libraries.navigation.AuthRequirement
import com.dangerfield.cards.libraries.navigation.GateReason
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Reads the latest auth + guest-creation state (cached from their flows) and
 * decides, synchronously, whether a route's [Route.authRequirement] is met —
 * the [com.dangerfield.cards.libraries.navigation.Router] calls [gate] inline on
 * the (non-suspending) navigate path, so the check has to be a cheap peek, not a
 * suspend.
 *
 * An [AutoInit] so it starts caching at app launch, before any gated navigation
 * can fire. Fail-closed: until auth resolves (cached null) an `Account` route is
 * gated — which is correct, because a gated route is only reachable via user
 * interaction well after auth has resolved.
 *
 * Not an `AppEventListener` (it depends on `AuthRepository`, which would form a
 * DI cycle through `AppEventBus`); `AutoInit` doesn't loop back.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthGateChecker::class)
@ContributesBinding(AppScope::class, boundType = AutoInit::class, multibinding = true)
@Inject
class RealAuthGateChecker(
    authRepository: AuthRepository,
    guestAccountCreator: GuestAccountCreator,
    appScope: AppCoroutineScope,
) : AuthGateChecker, AutoInit {

    private val authState = MutableStateFlow<AuthState?>(null)
    private val creationState = MutableStateFlow<AccountCreationState>(AccountCreationState.Idle)

    init {
        appScope.launch {
            Catching { authRepository.observe().collect { authState.value = it } }
                .logOnFailure { "Auth-gate auth observation failed" }
        }
        appScope.launch {
            Catching { guestAccountCreator.state.collect { creationState.value = it } }
                .logOnFailure { "Auth-gate creation observation failed" }
        }
    }

    override fun gate(route: Route): Route {
        val requirement = route.authRequirement
        if (requirement == AuthRequirement.None) return route

        val auth = authState.value
        val met = when (requirement) {
            AuthRequirement.None -> true
            AuthRequirement.Account -> auth is AuthState.Authenticated
            AuthRequirement.ClaimedAccount ->
                auth is AuthState.Authenticated && !auth.isAnonymous
        }
        if (met) return route

        return AuthGateRoute(reason = reasonFor(requirement, auth))
    }

    private fun reasonFor(requirement: AuthRequirement, auth: AuthState?): GateReason = when {
        // A guest account exists but the action needs it claimed.
        requirement == AuthRequirement.ClaimedAccount && auth is AuthState.Authenticated ->
            GateReason.NeedClaimedAccount
        // No account yet, but one is actively being created (degraded) — it heals.
        creationState.value is AccountCreationState.InProgress ||
            creationState.value is AccountCreationState.Failed -> GateReason.FinishingSetup
        else -> GateReason.NeedAccount
    }
}

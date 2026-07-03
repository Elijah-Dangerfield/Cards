package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.AuthGate
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.navigation.AuthGateChecker
import com.dangerfield.cards.libraries.navigation.AuthGateRoute
import com.dangerfield.cards.libraries.navigation.Route
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Thin adapter from the shared [AuthGate] to the router's [AuthGateChecker]
 * seam. All auth-readiness logic (including the fail-closed-while-resolving
 * behavior the non-suspending navigate path relies on) lives in the gate; this
 * only maps a Blocked verdict to the [AuthGateRoute] sheet.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AuthGateChecker::class)
@Inject
class RealAuthGateChecker(
    private val authGate: AuthGate,
) : AuthGateChecker {

    override fun gate(route: Route): Route =
        when (val verdict = authGate.verdict(route.authRequirement)) {
            AuthVerdict.Ready -> route
            is AuthVerdict.Blocked -> AuthGateRoute(reason = verdict.reason)
        }
}

package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.core.AuthGate
import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.core.AuthVerdict
import com.dangerfield.cards.libraries.navigation.AuthGateRoute
import com.dangerfield.cards.libraries.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Pins the adapter only: verdict → route substitution. The verdict table itself
 * (offline vs need-account, fail-closed pre-resolve, heal kicks, …) is pinned in
 * AuthGateImplTest — the checker owns none of that logic anymore.
 */
class RealAuthGateCheckerTest {

    @Test
    fun ready_passesTheRouteThroughByIdentity() {
        val route = Route(authRequirement = AuthRequirement.Account)
        val checker = RealAuthGateChecker(FakeAuthGate(AuthVerdict.Ready))

        assertSame(route, checker.gate(route))
    }

    @Test
    fun blocked_substitutesTheGateSheetRoute() {
        val checker = RealAuthGateChecker(FakeAuthGate(AuthVerdict.Blocked(AuthReason.Offline)))

        val gated = assertIs<AuthGateRoute>(checker.gate(Route(authRequirement = AuthRequirement.Account)))
        assertEquals(AuthReason.Offline, gated.reason)
    }

    @Test
    fun gate_consultsTheRoutesOwnRequirement() {
        val gate = FakeAuthGate(AuthVerdict.Ready)
        val checker = RealAuthGateChecker(gate)

        checker.gate(Route(authRequirement = AuthRequirement.ClaimedAccount))

        assertEquals(AuthRequirement.ClaimedAccount, gate.lastRequirement)
    }

    private class FakeAuthGate(private val verdict: AuthVerdict) : AuthGate {
        var lastRequirement: AuthRequirement? = null
            private set

        override fun verdict(requirement: AuthRequirement): AuthVerdict {
            lastRequirement = requirement
            return verdict
        }

        override suspend fun awaitVerdict(requirement: AuthRequirement): AuthVerdict =
            verdict(requirement)
    }
}

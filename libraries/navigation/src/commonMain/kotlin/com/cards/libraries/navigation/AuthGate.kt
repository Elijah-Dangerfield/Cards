package com.dangerfield.cards.libraries.navigation

/**
 * Decides whether a navigation is allowed for the current identity. Returns the
 * route to *actually* navigate to: the original when allowed, or an
 * [AuthGateRoute] when the route's [Route.authRequirement] isn't met.
 *
 * A thin nav-facing seam over the shared
 * [com.dangerfield.cards.libraries.core.AuthGate] — lives in
 * `:libraries:navigation` so the router can depend on it without depending on
 * `:libraries:identity`; the adapter is contributed from a module that sees both.
 */
interface AuthGateChecker {
    fun gate(route: Route): Route
}

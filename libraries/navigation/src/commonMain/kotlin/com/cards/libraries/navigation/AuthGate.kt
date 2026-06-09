package com.dangerfield.cards.libraries.navigation

import kotlinx.serialization.Serializable

/**
 * What a [Route] needs of the user's identity before it can be entered. Declared
 * per-route ([Route.authRequirement]) and enforced centrally at navigation time
 * by the [Router] via an [AuthGateChecker] — so a feature opts into gating with
 * one constructor arg, no per-screen guard code.
 */
@Serializable
enum class AuthRequirement {
    /** Anyone — the default. */
    None,

    /** A real (server) session of any kind, including a guest. Blocks the
     *  unauthenticated state (signed out, or a guest whose creation is still
     *  pending). Use for multiplayer, friends rooms, anything that hits the
     *  user's server-side account. */
    Account,

    /** A *claimed* (non-anonymous) account. Blocks guests too. Use for things a
     *  throwaway guest shouldn't do — e.g. real-money purchases. */
    ClaimedAccount,
}

/** Why a navigation was gated — drives the gate sheet's copy + CTA. */
@Serializable
enum class GateReason {
    /** Guest-account creation is still finishing (degraded). It self-heals; the
     *  sheet just asks the user to wait a moment. */
    FinishingSetup,

    /** No account at all — offer to create one / sign in. */
    NeedAccount,

    /** Has a guest account but the action needs a claimed one — offer to save it. */
    NeedClaimedAccount,
}

/**
 * Decides whether a navigation is allowed for the current identity. Returns the
 * route to *actually* navigate to: the original when allowed, or an
 * [AuthGateRoute] when the route's [Route.authRequirement] isn't met.
 *
 * Lives in `:libraries:navigation` so the router can depend on it without
 * depending on `:libraries:identity`; the real implementation (which reads
 * `AuthState` + guest-creation state) is contributed from a module that can see
 * both.
 */
interface AuthGateChecker {
    fun gate(route: Route): Route
}

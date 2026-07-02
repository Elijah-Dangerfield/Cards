package com.dangerfield.cards.libraries.navigation

import com.dangerfield.cards.libraries.core.AuthReason
import com.dangerfield.cards.libraries.core.AuthRequirement
import kotlinx.serialization.Serializable

/**
 * Shared destination the [Router] substitutes for a gated route when its
 * [Route.authRequirement] isn't met. Rendered as a bottom sheet / dialog (the
 * host registers it via `dialog<AuthGateRoute>`), so the user stays on their
 * current screen instead of being bounced into the feature and back.
 *
 * [reason] selects the copy + CTA. Carries no [authRequirement] itself (the
 * default [AuthRequirement.None]) so substituting it can't re-trigger the gate.
 */
@Serializable
data class AuthGateRoute(
    val reason: AuthReason,
) : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)

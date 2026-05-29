package com.dangerfield.cards.features.onboarding

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Email-password sign-in entry. Reachable from the intro pager's "Sign in"
 * affordance. After a successful sign-in (and `/v1/me` bootstrap) the
 * onboarding flag flips and we navigate to home.
 */
@Serializable
class SignInRoute : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)

/**
 * Sign-up entry. Reachable from the "Don't have an account?" link on
 * [SignInRoute]. On submission the user is sent a verification email and
 * we navigate to [VerifyEmailRoute].
 */
@Serializable
class SignUpRoute : Route(
    enter = AnimationType.SlideInFromRight,
    exit = AnimationType.SlideOutToLeft,
    popExit = AnimationType.SlideOutToRight,
)

/**
 * "Check your email" screen. Reachable after [SignUpRoute] submission and
 * via the `cards://auth/confirmed` deep-link bounced back from the
 * verification email.
 *
 * Email is nullable so the deep-link mapping can land here without the
 * URL having to carry the address (the cold-launch case where the user
 * killed the app before tapping the link in their inbox). The VM resolves
 * the missing email from the active Supabase session on init; the
 * screen falls back to a generic body string while the resolve is in
 * flight or if no email is on the session at all.
 */
@Serializable
data class VerifyEmailRoute(val email: String? = null) : Route(
    enter = AnimationType.SlideInFromRight,
    exit = AnimationType.SlideOutToLeft,
    popExit = AnimationType.SlideOutToRight,
)

/**
 * "Forgot password" email-entry screen. Reachable from [SignInRoute]'s
 * inline link. Submitting kicks off Supabase's password-reset email; the
 * recovery deep-link target (where the user actually picks a new password)
 * lives in a follow-up once the redirect URL is wired.
 */
@Serializable
class ForgotPasswordRoute : Route(
    enter = AnimationType.SlideInFromRight,
    exit = AnimationType.SlideOutToLeft,
    popExit = AnimationType.SlideOutToRight,
)

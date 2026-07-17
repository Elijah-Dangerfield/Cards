package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/** Edit display name + emoji avatar. Backed by PATCH /v1/me. */
@Serializable
class EditProfileRoute : Route()

/**
 * In-app notifications inbox — server-scheduled messages of kind
 * `inbox`. The screen marks all unread as shown on resume; the next
 * sync ships the batched ack ids in its POST body.
 */
@Serializable
class NotificationsRoute : Route()

/** Type-to-confirm account deletion. Backed by DELETE /v1/me + Supabase Admin. */
@Serializable
class DeleteAccountRoute : Route()

/**
 * Apple/Google claim flow on top of the current anonymous Supabase session.
 * Providers are gated by the identity config flags — when OAuth creds
 * aren't provisioned, the screen surfaces email/password + a coming-soon
 * notice for the social buttons.
 */
@Serializable
class ClaimAccountRoute : Route()

/**
 * Celebratory confirmation shown after an anonymous guest successfully links a
 * real identity (Google/Apple). Distinct from the sign-up welcome and the plain
 * sign-in bounce: it reassures the user their existing guest progress is now
 * saved. Routed (not derived state) so it owns its own back-stack lifecycle and
 * animates in over Profile, mirroring the Home welcome dialog. [providerLabel]
 * is the display name of the linked provider ("Google" / "Apple").
 */
@Serializable
data class AccountLinkedRoute(
    val providerLabel: String,
) : Route(
    enter = AnimationType.SlideUp,
    exit = AnimationType.SlideDown,
    popExit = AnimationType.SlideDown,
)

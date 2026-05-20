package com.dangerfield.cards.features.profile

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
 * Providers are gated by IdentityFeatureConfig flags — when OAuth creds
 * aren't provisioned, the screen surfaces email/password + a coming-soon
 * notice for the social buttons.
 */
@Serializable
class ClaimAccountRoute : Route()

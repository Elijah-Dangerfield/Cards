package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/** Edit display name + emoji avatar. Backed by PATCH /v1/me. */
@Serializable
class EditProfileRoute : Route()

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

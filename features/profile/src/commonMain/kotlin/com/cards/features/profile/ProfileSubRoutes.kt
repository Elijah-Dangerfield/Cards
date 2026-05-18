package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/** Edit display name + avatar. V1 is a placeholder screen. */
@Serializable
class EditProfileRoute : Route()

/** Confirm + execute account deletion. V1 placeholder. */
@Serializable
class DeleteAccountRoute : Route()

/** Apple/Google claim flow. V1 placeholder until Phase 3 auth lands. */
@Serializable
class ClaimAccountRoute : Route()

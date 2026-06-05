package com.dangerfield.cards.features.profile

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * App settings, reached from the gear in the Profile's top-right. Holds the
 * gameplay toggles, notifications, support/about links, and the account
 * actions (claim / sign out / delete) that used to live inline on the
 * Profile tab before the profile became a real, show-off profile.
 *
 * Parameterless `class` (not `data object`) per the iOS navigator
 * requirement — a `data object` route SIGSEGVs at navigate-time.
 */
@Serializable
class SettingsRoute : Route()

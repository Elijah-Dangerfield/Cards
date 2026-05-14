package com.dangerfield.cards.features.progression

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Slide-up XP detail sheet. Opened from the home and profile XP entry points.
 * Registered as a bottom-sheet destination (see `XpDetailSheetEntryPoint`),
 * so the host nav infrastructure handles the slide animation.
 */
@Serializable
class XpDetailSheetRoute : Route()

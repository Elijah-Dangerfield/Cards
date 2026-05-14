package com.dangerfield.cards.features.progression

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Slide-up Rank detail sheet. Opened from the home and profile rank entry
 * points. Copy inside the sheet branches on whether the user is anonymous —
 * anon users are told that rank locks in once they claim their account and
 * play multiplayer (see docs/decisions.md 2026-05-14).
 */
@Serializable
class RankDetailSheetRoute : Route()

package com.dangerfield.cards.features.rooms

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * The PUBLIC rooms route family — zero-friction, auto-seated, no host. The
 * player picks a buy-in range and the matchmaker seats them; the server (not a
 * host) deals. See `docs/design-handoff/rooms/SPEC.md`.
 *
 * Find → Searching is live matchmaking (Phase 5); Searching hands straight off
 * to the multiplayer table once a hand deals. Lobby / NextRound remain as the
 * mid-hand-join shells.
 *
 * All routes are `class` / `data class` (never `data object`): a serializable
 * `data object` route crashes the iOS navigator at navigate-time.
 */

/** Set the buy-in RANGE you're comfortable with, then ask to be seated. */
@Serializable
class PublicFindRoute : Route()

/**
 * Live matchmaking — the radar, the found-count, the honest disclosed-bot offer
 * if the search comes up empty. Carries the buy-in [minBuyIn]..[maxBuyIn] range
 * the player set on Find so the search runs at their chosen stakes.
 */
@Serializable
data class PublicSearchingRoute(
    val minBuyIn: Long,
    val maxBuyIn: Long,
) : Route()

/** Matched into a table that hasn't dealt yet; a timer auto-deals. */
@Serializable
data class PublicLobbyRoute(val tableId: String) : Route()

/** Matched into a table mid-hand; you're dealt in next round. */
@Serializable
data class PublicNextRoundRoute(val tableId: String) : Route()

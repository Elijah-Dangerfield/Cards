package com.dangerfield.cards.features.rooms

import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * The PUBLIC rooms route family — zero-friction, auto-seated, no host. The
 * player picks a buy-in range and the matchmaker seats them; a timer (not a
 * host) deals. See `docs/design-handoff/rooms/SPEC.md`.
 *
 * Real matchmaking is not wired yet — these screens are visual shells. The
 * flow walks Find → Searching → Lobby / NextRound.
 *
 * All routes are `class` / `data class` (never `data object`): a serializable
 * `data object` route crashes the iOS navigator at navigate-time.
 */

/** Set the buy-in RANGE you're comfortable with, then ask to be seated. */
@Serializable
class PublicFindRoute : Route()

/** Matchmaking loading state — the radar, "holding seats", a cancel hatch. */
@Serializable
class PublicSearchingRoute : Route()

/** Matched into a table that hasn't dealt yet; a timer auto-deals. */
@Serializable
data class PublicLobbyRoute(val tableId: String) : Route()

/** Matched into a table mid-hand; you're dealt in next round. */
@Serializable
data class PublicNextRoundRoute(val tableId: String) : Route()

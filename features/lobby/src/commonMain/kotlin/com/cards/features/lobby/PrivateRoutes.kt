package com.dangerfield.cards.features.lobby

import com.dangerfield.cards.libraries.core.AuthRequirement
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * The private-room front-ends reached from the Home [PrivateChoose] sheet
 * (SPEC §6–7). Both are thin screens that funnel into the existing seated
 * lobby ([LobbyRoute]) — Create spins up a room via `autoCreate`, Join attempts
 * a join via `prefilledCode`. No new backend wiring.
 *
 * `class` / `data class` (never `data object`) — a serializable `data object`
 * route crashes the iOS navigator at navigate-time.
 */

/** Set up the room you're hosting (name, stakes, max players), then create. */
@Serializable
class PrivateCreateRoute : Route(authRequirement = AuthRequirement.Account)

/**
 * Enter a host's 6-character code to join their private table.
 *
 * [rejectedCode] re-seeds the screen after a join bounced back: the
 * prefilled-code lobby pops itself and routes here with the bad code so the
 * user lands back on the input (not stranded on a dead lobby spinner) with an
 * inline "room not found" error and the code pre-filled to retry. Null = a
 * fresh entry from the Home join sheet.
 *
 * `data class` (never `data object`) — a serializable `data object` route
 * crashes the iOS navigator at navigate-time; a `data class` carrying args is
 * safe (mirrors [LobbyRoute]).
 */
@Serializable
data class PrivateJoinRoute(
    val rejectedCode: String? = null,
) : Route(authRequirement = AuthRequirement.Account)

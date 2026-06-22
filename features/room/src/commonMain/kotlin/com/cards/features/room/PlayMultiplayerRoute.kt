package com.dangerfield.cards.features.room

import com.dangerfield.cards.libraries.navigation.AuthRequirement
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Multiplayer-hand route. The room code identifies the live server-
 * authoritative session; the play screen subscribes to that room's
 * WebSocket via [com.dangerfield.cards.libraries.rooms.RoomRepository.connect]
 * and renders whatever the server says is happening.
 *
 * Declared as a `data class` (not `data object`) per
 * [docs/decisions.md] — `data object` routes crash the iOS navigator
 * at navigate-time. Even the no-arg case takes a `class` declaration.
 */
@Serializable
data class PlayMultiplayerRoute(
    val roomCode: String,
    /**
     * Whether this is a private (invite/code) game or a public (matchmade)
     * one. Drives where the player is routed when every opponent leaves: back
     * to the lobby for private, to matchmaking search for public. Defaults to
     * [RoomKind.Private] — the only flow wired to real server rooms today; the
     * public path is reachable once matchmaking lands.
     */
    val kind: RoomKind = RoomKind.Private,
) : Route(authRequirement = AuthRequirement.Account)

/** How a multiplayer game was entered — see [PlayMultiplayerRoute.kind]. */
@Serializable
enum class RoomKind { Private, Public }

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
) : Route(authRequirement = AuthRequirement.Account)

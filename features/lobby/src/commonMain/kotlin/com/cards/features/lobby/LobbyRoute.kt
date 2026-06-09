package com.dangerfield.cards.features.lobby

import com.dangerfield.cards.libraries.navigation.AuthRequirement
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Multiplayer lobby entry. From here the user creates a fresh room
 * (gets a code to share) or joins by typing a friend's code. Once
 * connected, shows the live member list and a leave button.
 *
 * [prefilledCode] supports deep-link "join code XYZ" — the lobby
 * pre-fills the code field and auto-attempts a join on entry. Null =
 * normal lobby entry from the home screen.
 *
 * [autoCreate] is the create-side counterpart: the Friend Game "Create a
 * room" sheet routes here with it set so the lobby spins up a fresh room
 * on entry and lands the user seated (with a code to share) instead of on
 * the idle create/join screen. Ignored when [prefilledCode] is also set.
 */
@Serializable
data class LobbyRoute(
    val prefilledCode: String? = null,
    val autoCreate: Boolean = false,
) : Route(authRequirement = AuthRequirement.Account)

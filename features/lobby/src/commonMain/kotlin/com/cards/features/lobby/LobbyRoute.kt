package com.dangerfield.cards.features.lobby

import com.dangerfield.cards.libraries.core.AuthRequirement
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
 *
 * [maxSeats] carries the host's chosen table size from the create screen into
 * the auto-create. Null falls back to the server default. Only meaningful
 * alongside [autoCreate].
 */
@Serializable
data class LobbyRoute(
    val prefilledCode: String? = null,
    val autoCreate: Boolean = false,
    val maxSeats: Int? = null,
    /** Host-chosen buy-in carried from the create screen into the auto-create.
     *  Null falls back to the server default. Only meaningful with [autoCreate]. */
    val buyIn: Long? = null,
    /** "Open to anyone": create the room matchmaker-discoverable + server-dealt.
     *  Only meaningful with [autoCreate]. */
    val open: Boolean = false,
    /** Host-picked table felt (SHOP-5), carried from the create screen's picker.
     *  Null falls back to the host's equipped felt. Only meaningful with [autoCreate]. */
    val feltProductId: String? = null,
    /** Host-picked card back (SHOP-5). Null falls back to the host's equipped
     *  card back. Only meaningful with [autoCreate]. */
    val cardBackProductId: String? = null,
) : Route(authRequirement = AuthRequirement.Account)

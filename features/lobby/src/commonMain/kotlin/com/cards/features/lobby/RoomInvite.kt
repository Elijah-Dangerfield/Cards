package com.dangerfield.cards.features.lobby

/**
 * One source of truth for the room-invite deep link. The lobby registers a
 * `cards://join/{prefilledCode}` deep link on [LobbyRoute]; this builds the
 * matching URL for the share affordance, so the two can't drift apart.
 */
object RoomInvite {
    const val DEEP_LINK_BASE_PATH: String = "cards://join"

    fun linkForCode(code: String): String = "$DEEP_LINK_BASE_PATH/$code"
}

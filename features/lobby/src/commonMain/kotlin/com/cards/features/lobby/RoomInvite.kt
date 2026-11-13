package com.dangerfield.cards.features.lobby

/**
 * One source of truth for room-invite links.
 *
 * [linkForCode] is the app's own `cards://join/{prefilledCode}` deep link, which
 * the lobby registers on [LobbyRoute]. It only works where the app is installed,
 * and Messages will not even make it tappable, so what people actually share is
 * [shareUrlForCode]: a downcard.app page that opens the app through that deep
 * link, or sends a friend without it to the store with the code on screen.
 */
object RoomInvite {
    const val DEEP_LINK_BASE_PATH: String = "cards://join"
    const val SHARE_BASE_URL: String = "https://downcard.app/join"

    fun linkForCode(code: String): String = "$DEEP_LINK_BASE_PATH/$code"

    fun shareUrlForCode(code: String): String = "$SHARE_BASE_URL/$code"
}

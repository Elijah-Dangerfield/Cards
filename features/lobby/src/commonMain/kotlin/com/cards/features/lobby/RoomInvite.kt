package com.dangerfield.cards.features.lobby

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.standing_table_share_message
import org.jetbrains.compose.resources.getString

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

    fun linkForCode(code: String): String = "$DEEP_LINK_BASE_PATH/$code"

    fun shareUrlForCode(code: String, baseUrl: String): String = "${baseUrl.trimEnd('/')}/$code"

    suspend fun standingShareText(name: String, weekday: String, time: String, code: String, baseUrl: String): String =
        getString(Res.string.standing_table_share_message, name, weekday, time, code, shareUrlForCode(code, baseUrl))
}

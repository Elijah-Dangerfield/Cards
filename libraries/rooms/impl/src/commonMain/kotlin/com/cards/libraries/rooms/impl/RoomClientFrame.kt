package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for client → server socket frames. Mirrors
 * `apps/server/.../routes/RoomClientFrame.kt`. The discriminator is
 * `type`; same [RoomSocketJson] config as the server-bound events.
 *
 * Three variants today, all game-related:
 *  - [StartHand] — host opens a hand.
 *  - [SubmitIntent] — actor submits a fold/check/call/bet/raise/all-in.
 *  - [RequestNextHand] — any seated player advances after a hand ends.
 *
 * Each carries a [clientNonce] so the server's matching
 * [RoomSocketEventDto.IntentAck] can route back to the originating call
 * site (and so server-side dedupe can collapse retries).
 */
@Serializable
internal sealed interface RoomClientFrame {
    val clientNonce: String

    @Serializable
    @SerialName("start_hand")
    data class StartHand(override val clientNonce: String) : RoomClientFrame

    @Serializable
    @SerialName("submit_intent")
    data class SubmitIntent(
        val intent: PlayerIntent,
        override val clientNonce: String,
    ) : RoomClientFrame

    @Serializable
    @SerialName("request_next_hand")
    data class RequestNextHand(override val clientNonce: String) : RoomClientFrame
}

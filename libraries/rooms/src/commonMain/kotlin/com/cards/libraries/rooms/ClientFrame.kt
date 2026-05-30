package com.dangerfield.cards.libraries.rooms

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client → server frames written over the room WebSocket. Matches the
 * server's `RoomClientFrame` wire format; the same `@SerialName`s flow
 * across the wire unchanged.
 *
 * Three variants:
 *  - [StartHand] — the current host opens a hand.
 *  - [SubmitIntent] — the acting seat submits fold / check / call / bet
 *    / raise / all-in.
 *  - [RequestNextHand] — any seated player advances after a hand ends.
 *
 * Each carries a [clientNonce] so the server's matching `IntentAck` can
 * route back to the originating caller and so server-side dedupe can
 * collapse retries.
 */
@Serializable
sealed interface ClientFrame {
    val clientNonce: String

    @Serializable
    @SerialName("start_hand")
    data class StartHand(override val clientNonce: String) : ClientFrame

    @Serializable
    @SerialName("submit_intent")
    data class SubmitIntent(
        val intent: PlayerIntent,
        override val clientNonce: String,
    ) : ClientFrame

    @Serializable
    @SerialName("request_next_hand")
    data class RequestNextHand(override val clientNonce: String) : ClientFrame
}

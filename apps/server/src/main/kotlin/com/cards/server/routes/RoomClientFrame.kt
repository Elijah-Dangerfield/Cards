package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client → server messages over the per-room WebSocket.
 *
 * The original V1 socket was strictly server→client ("V1 doesn't define
 * a client→server message type — clients just listen" — see
 * [RoomSocketEventDto] header). Multiplayer brought in three game
 * actions that need to round-trip from client to server:
 *
 *  - [StartHand] — host taps "Start game" in the lobby. Server runs
 *    `GameEngine.startHand(...)`, transitions [RoomStatus] to `Playing`,
 *    begins broadcasting per-subscriber [RoomSocketEventDto.GameStateSnapshot]s.
 *  - [SubmitIntent] — a seated player makes an action (fold / check /
 *    call / bet / raise / all-in). Server validates `actorSeatIndex`
 *    matches the caller's seat, dedupes on [clientNonce], applies via
 *    [com.cards.libraries.gameplay.GameEngine.applyIntent].
 *  - [RequestNextHand] — any seated player taps "next hand" at the end
 *    of the previous one. Server idempotently advances; second caller
 *    races see the same new hand.
 *
 * Each variant carries a [clientNonce] so the server can return a
 * matching [RoomSocketEventDto.IntentAck] (and dedupe retries). Clients
 * generate the nonce; collisions across clients are fine — dedupe is
 * scoped per-room.
 *
 * Forward-compat: server's drain loop wraps decode in try/catch and
 * drops unknown variants, symmetric with the client's
 * [com.cards.libraries.rooms.impl.ReconnectingRoomSocket] handling.
 */
@Serializable
sealed interface RoomClientFrame {
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

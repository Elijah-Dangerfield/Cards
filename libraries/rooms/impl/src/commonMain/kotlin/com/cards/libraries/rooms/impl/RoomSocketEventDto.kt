package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for server → client socket events. Mirrors
 * `apps/server/.../routes/RoomSocketEvent.kt`. The discriminator is
 * `type`; the Json instance in [RoomSocketJson] sets it.
 *
 * Unknown / forward-compat handling lives at the call site in
 * [ReconnectingRoomSocket] — it catches deserialization failures and
 * drops the frame rather than crashing the flow. Keeps a stale client
 * tolerant of any new server-side variants without registering
 * placeholders here.
 *
 * Variants split into two families:
 *  - Lobby + presence (Snapshot, MemberJoined, MemberLeft,
 *    MemberPresenceChanged, RoomClosed) — what the room *is*.
 *  - Multiplayer gameplay (GameStateSnapshot, GameEventOccurred,
 *    IntentAck) — what the in-progress hand is doing. The server
 *    personalizes [GameStateSnapshot] per subscriber so other seats'
 *    hole cards are scrubbed.
 */
@Serializable
internal sealed interface RoomSocketEventDto {

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(val room: RoomDto) : RoomSocketEventDto

    @Serializable
    @SerialName("member_joined")
    data class MemberJoined(val member: RoomMemberDto) : RoomSocketEventDto

    @Serializable
    @SerialName("member_left")
    data class MemberLeft(val userId: String) : RoomSocketEventDto

    @Serializable
    @SerialName("member_presence_changed")
    data class MemberPresenceChanged(
        val userId: String,
        val isConnected: Boolean,
    ) : RoomSocketEventDto

    @Serializable
    @SerialName("room_closed")
    data object RoomClosed : RoomSocketEventDto

    @Serializable
    @SerialName("game_state")
    data class GameStateSnapshot(val state: GameState) : RoomSocketEventDto

    @Serializable
    @SerialName("game_event")
    data class GameEventOccurred(
        val seq: Long,
        val event: GameEvent,
    ) : RoomSocketEventDto

    @Serializable
    @SerialName("intent_ack")
    data class IntentAck(
        val clientNonce: String,
        val accepted: Boolean,
        val error: String? = null,
    ) : RoomSocketEventDto
}

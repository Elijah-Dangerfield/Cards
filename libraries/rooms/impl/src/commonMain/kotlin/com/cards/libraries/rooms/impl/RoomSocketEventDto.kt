package com.dangerfield.cards.libraries.rooms.impl

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
}

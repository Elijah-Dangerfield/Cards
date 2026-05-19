package com.dangerfield.cards.server.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server → client messages over the per-room WebSocket. Sealed with a
 * `@SerialName` polymorphic class discriminator so adding a new event
 * type doesn't break existing clients (Kotlinx Serialization defaults
 * to throwing on unknown types — clients use `ignoreUnknownKeys` +
 * `classDiscriminatorMode` overrides, see the client's RoomEventDto).
 *
 * Event shape:
 *  - [Snapshot] fires on connect (and after the server applies a
 *    mutation initiated by another client). Carries the full room.
 *    The client treats it as state-of-the-world, last-write-wins.
 *  - [MemberJoined] / [MemberLeft] / [MemberPresenceChanged] are
 *    convenience deltas the UI can use for toasts ("Alice joined")
 *    without diffing snapshots. The Snapshot is still authoritative.
 *  - [RoomClosed] fires when the last member leaves; clients should
 *    drop their connection + return to lobby selection.
 *
 * V1 doesn't define a client→server message type — clients just listen.
 * Mutations go via HTTP (POST /v1/rooms/{code}/join etc.) so the wire
 * format stays one-way and easy to reason about.
 */
@Serializable
sealed interface RoomSocketEventDto {

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

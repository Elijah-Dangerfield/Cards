package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.Room
import com.dangerfield.cards.server.domain.RoomMember
import com.dangerfield.cards.server.domain.RoomStatus
import kotlinx.serialization.Serializable
import kotlin.time.ExperimentalTime

/**
 * Wire format for the room endpoints. Domain types stay server-internal
 * so the wire shape can evolve independently — UserId becomes a string
 * here, Instant becomes epoch ms, etc.
 *
 * camelCase to match the rest of the API. `schemaVersion` on the
 * envelopes gives us a release-time breaking-change hatch without
 * inventing one per type.
 */
@Serializable
data class RoomDto(
    val code: String,
    val hostUserId: String,
    val createdAtEpochMs: Long,
    val maxSeats: Int,
    val status: RoomStatusDto,
    val members: List<RoomMemberDto>,
)

@Serializable
data class RoomMemberDto(
    val userId: String,
    val displayName: String,
    val seatIndex: Int,
    val joinedAtEpochMs: Long,
    val isConnected: Boolean,
)

@Serializable
enum class RoomStatusDto { Lobby, Playing, Finished }

/**
 * POST /v1/rooms body. `maxSeats` defaults to the server's V1 cap
 * — accepting it in the body lets future tournament-shaped rooms
 * negotiate without a new endpoint.
 */
@Serializable
data class CreateRoomRequest(
    val maxSeats: Int? = null,
)

@Serializable
data class CreateRoomResponse(
    val schemaVersion: Int = 1,
    val room: RoomDto,
)

@Serializable
data class JoinRoomResponse(
    val schemaVersion: Int = 1,
    val room: RoomDto,
    /** True when the join was an idempotent no-op (member already in room). */
    val alreadyJoined: Boolean = false,
)

@Serializable
data class GetRoomResponse(
    val schemaVersion: Int = 1,
    val room: RoomDto,
)

@OptIn(ExperimentalTime::class)
internal fun Room.toDto(): RoomDto = RoomDto(
    code = code,
    hostUserId = hostUserId.value.toString(),
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
    maxSeats = maxSeats,
    status = status.toDto(),
    members = members.map { it.toDto() },
)

@OptIn(ExperimentalTime::class)
internal fun RoomMember.toDto(): RoomMemberDto = RoomMemberDto(
    userId = userId.value.toString(),
    displayName = displayName,
    seatIndex = seatIndex,
    joinedAtEpochMs = joinedAt.toEpochMilliseconds(),
    isConnected = isConnected,
)

internal fun RoomStatus.toDto(): RoomStatusDto = when (this) {
    RoomStatus.Lobby -> RoomStatusDto.Lobby
    RoomStatus.Playing -> RoomStatusDto.Playing
    RoomStatus.Finished -> RoomStatusDto.Finished
}

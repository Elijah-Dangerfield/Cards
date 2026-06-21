package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format mirroring `apps/server/.../routes/RoomDto.kt`. Internal
 * to `:libraries:rooms:impl`; the api consumes the
 * [com.dangerfield.cards.libraries.rooms.Room] domain shape.
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
    val isBot: Boolean = false,
    val avatarEmoji: String? = null,
    val avatarBackgroundColor: String? = null,
)

/**
 * Includes [Unknown] as a safety valve — server schema additions
 * decode as Unknown instead of crashing the flow. The client renders
 * Unknown as "Lobby" pessimistically (treat as still-open) until a
 * follow-up snapshot arrives.
 */
@Serializable
enum class RoomStatusDto {
    @SerialName("Lobby") Lobby,
    @SerialName("Playing") Playing,
    @SerialName("Finished") Finished,
    @SerialName("Unknown") Unknown,
}

@Serializable
data class CreateRoomRequestDto(val maxSeats: Int? = null)

@Serializable
data class CreateRoomResponseDto(
    val schemaVersion: Int = 1,
    val room: RoomDto,
)

@Serializable
data class JoinRoomResponseDto(
    val schemaVersion: Int = 1,
    val room: RoomDto,
    val alreadyJoined: Boolean = false,
)

@Serializable
data class GetRoomResponseDto(
    val schemaVersion: Int = 1,
    val room: RoomDto,
)

@Serializable
data class AddBotRequestDto(
    val seatIndex: Int? = null,
    val difficulty: String? = null,
)

@Serializable
data class AddBotResponseDto(
    val schemaVersion: Int = 1,
    val room: RoomDto,
)

@Serializable
data class ActiveRoomsResponseDto(
    val schemaVersion: Int = 1,
    val rooms: List<RoomDto> = emptyList(),
)

@Serializable
data class ProblemEnvelopeDto(
    val error: ProblemDto,
)

@Serializable
data class ProblemDto(
    val code: String,
    val message: String,
)

internal fun RoomDto.toDomain(): Room = Room(
    code = code,
    hostUserId = hostUserId,
    createdAtEpochMs = createdAtEpochMs,
    maxSeats = maxSeats,
    status = status.toDomain(),
    members = members.map { it.toDomain() },
)

internal fun RoomMemberDto.toDomain(): RoomMember = RoomMember(
    userId = userId,
    displayName = displayName,
    seatIndex = seatIndex,
    joinedAtEpochMs = joinedAtEpochMs,
    isConnected = isConnected,
    isBot = isBot,
    avatarEmoji = avatarEmoji,
    avatarBackgroundColorHex = avatarBackgroundColor,
)

internal fun RoomStatusDto.toDomain(): RoomStatus = when (this) {
    RoomStatusDto.Lobby -> RoomStatus.Lobby
    RoomStatusDto.Playing -> RoomStatus.Playing
    RoomStatusDto.Finished -> RoomStatus.Finished
    RoomStatusDto.Unknown -> RoomStatus.Unknown
}

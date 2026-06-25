package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.rooms.Room
import com.dangerfield.cards.libraries.rooms.RoomMember
import com.dangerfield.cards.libraries.rooms.RoomStatus
import com.dangerfield.cards.libraries.rooms.RoomVisibility
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
    // Defaulted to [RoomStatusDto.Unknown] so a future server status this client
    // doesn't know coerces here (release Json has coerceInputValues on) instead of
    // throwing — coercion needs a property default to land on, and without one an
    // unrecognised status crashes the whole room-snapshot decode. Status is always
    // present in practice, so the default only ever fires as that safety valve.
    val status: RoomStatusDto = RoomStatusDto.Unknown,
    val members: List<RoomMemberDto>,
    val buyIn: Long = 0,
    val smallBlind: Long = 0,
    val bigBlind: Long = 0,
    val visibility: RoomVisibilityDto = RoomVisibilityDto.Private,
)

@Serializable
enum class RoomVisibilityDto {
    @SerialName("Private") Private,
    @SerialName("Open") Open,
    @SerialName("Public") Public,

    /**
     * Only matched when the server literally sends "Unknown". Genuine forward-
     * compat (a future 4th visibility) doesn't land here: release Json's
     * coerceInputValues coerces an unrecognised value to the *property default*,
     * which for [RoomDto.visibility] is [Private] — a safe pessimistic read (treat
     * an unknown room as code-only, never matchmaking-discoverable). Kept for
     * parallelism with [RoomStatusDto.Unknown] and an explicit domain mapping.
     */
    @SerialName("Unknown") Unknown,
}

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
 * [Unknown] is the real safety valve: [RoomDto.status] defaults to it, so release
 * Json's coerceInputValues coerces a server status this client doesn't know to
 * Unknown instead of crashing the room-snapshot decode. The client renders Unknown
 * as "Lobby" pessimistically (treat as still-open) until a follow-up snapshot
 * arrives. (A literal "Unknown" from the server maps here too.)
 */
@Serializable
enum class RoomStatusDto {
    @SerialName("Lobby") Lobby,
    @SerialName("Playing") Playing,
    @SerialName("Finished") Finished,
    @SerialName("Unknown") Unknown,
}

@Serializable
data class CreateRoomRequestDto(
    val maxSeats: Int? = null,
    val buyIn: Long? = null,
    /** "Open" makes the room matchmaker-discoverable + server-dealt; null = Private. */
    val visibility: String? = null,
)

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

/** POST /v1/matchmaking/find body — the buy-in range the searcher set. */
@Serializable
data class MatchmakingFindRequestDto(
    val minBuyIn: Long,
    val maxBuyIn: Long,
)

/** Response for both /v1/matchmaking/find and /v1/matchmaking/{code}/play-bots. */
@Serializable
data class MatchmakingFindResponseDto(
    val schemaVersion: Int = 1,
    val room: RoomDto,
    val created: Boolean = false,
)

/** GET /v1/matchmaking/candidates response — the qualifying tables, most-humans-first. */
@Serializable
data class MatchmakingCandidatesResponseDto(
    val schemaVersion: Int = 1,
    val rooms: List<RoomDto> = emptyList(),
)

/** GET /v1/matchmaking/subsidy-budget response — the caller's subsidy draw-down. */
@Serializable
data class SubsidyBudgetResponseDto(
    val schemaVersion: Int = 1,
    val grantedToday: Long,
    val cap: Long,
    val remaining: Long,
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
    buyIn = buyIn,
    smallBlind = smallBlind,
    bigBlind = bigBlind,
    visibility = visibility.toDomain(),
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

internal fun RoomVisibilityDto.toDomain(): RoomVisibility = when (this) {
    RoomVisibilityDto.Private -> RoomVisibility.Private
    RoomVisibilityDto.Open -> RoomVisibility.Open
    RoomVisibilityDto.Public -> RoomVisibility.Public
    RoomVisibilityDto.Unknown -> RoomVisibility.Unknown
}

package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.gameplay.RoomSettings
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
    /** Host-chosen buy-in (= starting stack) and the blinds derived from it,
     *  so the client renders real stakes instead of placeholders. */
    val buyIn: Long = RoomSettings.DEFAULT_BUY_IN,
    val smallBlind: Long = 0,
    val bigBlind: Long = 0,
)

@Serializable
data class RoomMemberDto(
    val userId: String,
    val displayName: String,
    val seatIndex: Int,
    val joinedAtEpochMs: Long,
    val isConnected: Boolean,
    /**
     * True only for a *revealed* bot. Hidden bots and humans are false — this
     * is the lobby chokepoint of the stealth model (the other being the
     * scrubbed game-state snapshot). The client renders the BOT badge from it.
     */
    val isBot: Boolean = false,
    /**
     * Avatar emoji + background color, snapshotted at join. Sent for every
     * member so opponents render the real avatar instead of an initial; for a
     * revealed bot this carries the reserved robot emoji. Null/blank → the
     * client's initials fallback.
     */
    val avatarEmoji: String? = null,
    val avatarBackgroundColor: String? = null,
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
    /** Host-chosen buy-in. Null = server default. Validated against
     *  [RoomSettings.MIN_BUY_IN]..[RoomSettings.MAX_BUY_IN]. */
    val buyIn: Long? = null,
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

/**
 * POST /v1/rooms/{code}/bots body. [seatIndex] null fills the next free seat.
 * [difficulty] is the bot tier name ("Casual" | "Standard" | "Challenging");
 * null defaults to Standard. Personality is auto-assigned server-side.
 */
@Serializable
data class AddBotRequest(
    val seatIndex: Int? = null,
    val difficulty: String? = null,
)

@Serializable
data class AddBotResponse(
    val schemaVersion: Int = 1,
    val room: RoomDto,
)

@Serializable
data class ActiveRoomsResponse(
    val schemaVersion: Int = 1,
    val rooms: List<RoomDto>,
)

@OptIn(ExperimentalTime::class)
internal fun Room.toDto(): RoomDto = RoomDto(
    code = code,
    hostUserId = hostUserId.value.toString(),
    createdAtEpochMs = createdAt.toEpochMilliseconds(),
    maxSeats = maxSeats,
    status = status.toDto(),
    members = members.map { it.toDto() },
    buyIn = buyIn,
    smallBlind = settings.smallBlind,
    bigBlind = settings.bigBlind,
)

@OptIn(ExperimentalTime::class)
internal fun RoomMember.toDto(): RoomMemberDto {
    // Stealth chokepoint: only a *revealed* bot is advertised as one. A hidden
    // bot (future matchmaking auto-fill) flows through here looking exactly like
    // a human — isBot=false, ordinary avatar — so nothing on the wire reveals it.
    val revealed = bot?.revealed == true
    return RoomMemberDto(
        userId = userId.value.toString(),
        displayName = displayName,
        seatIndex = seatIndex,
        joinedAtEpochMs = joinedAt.toEpochMilliseconds(),
        // Bots are always "present"; never surface their bookkeeping connected flag.
        isConnected = if (isBot) true else isConnected,
        isBot = revealed,
        avatarEmoji = avatarEmoji.ifBlank { null },
        avatarBackgroundColor = avatarBackgroundColor,
    )
}

internal fun RoomStatus.toDto(): RoomStatusDto = when (this) {
    RoomStatus.Lobby -> RoomStatusDto.Lobby
    RoomStatus.Playing -> RoomStatusDto.Playing
    RoomStatus.Finished -> RoomStatusDto.Finished
}

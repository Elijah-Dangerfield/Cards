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
    /**
     * Who can discover + how the table deals. The client reads this to know an
     * Open/Public table is server-dealt (no host Start, auto-follow into the
     * game) vs a Private table the host starts. Defaulted so pre-visibility
     * clients/rows read as Private.
     */
    val visibility: RoomVisibilityDto = RoomVisibilityDto.Private,
    /**
     * Host-chosen table cosmetics (SHOP-3): the felt + card-back catalog product
     * ids the host had equipped at create time. Every client renders the host's
     * look instead of its own equipped cosmetic. Null = host had nothing equipped
     * in that slot, so the client falls back to the player's own felt/card back.
     */
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
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

@Serializable
enum class RoomVisibilityDto { Private, Open, Public }

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
    /**
     * "Open to anyone" toggle. `null`/"Private" → a code-only room; "Open" → a
     * matchmaker-discoverable, server-dealt table the host can still share by
     * code. "Public" is rejected — only the matchmaker mints those.
     */
    val visibility: String? = null,
    /**
     * Host's equipped felt + card-back catalog product ids at create time
     * (SHOP-3). Echoed onto the room snapshot so every player renders the host's
     * table look. Null = nothing equipped in that slot. Stored opaquely — the
     * server never maps them to a style.
     */
    val feltProductId: String? = null,
    val cardBackProductId: String? = null,
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
 * DELETE /v1/rooms/{code}/me response body, returned with 200 when the leave
 * cashed the player's table stack back to their wallet (MP-29). [balance] is the
 * authoritative post-settlement wallet balance, so the client's leave call *is*
 * the reconcile — no speculative sync that could race the server's cash-out. A
 * leave with nothing to settle (lobby / bot-only / an all-in-live deferral whose
 * balance lands later over the socket) returns 204 with no body instead.
 */
@Serializable
data class LeaveRoomResponse(
    val schemaVersion: Int = 1,
    val balance: Long,
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

/**
 * POST /v1/matchmaking/find body — the buy-in RANGE the searcher set on the
 * Find screen. The matchmaker seats them into an eligible room whose buy-in is
 * in `[minBuyIn, maxBuyIn]`, else opens a fresh public table snapped to a
 * canonical tier in range.
 */
@Serializable
data class MatchmakingFindRequest(
    val minBuyIn: Long,
    val maxBuyIn: Long,
)

@Serializable
data class MatchmakingFindResponse(
    val schemaVersion: Int = 1,
    val room: RoomDto,
    /**
     * True when the matchmaker opened a NEW table for this searcher (no
     * eligible room existed), false when it seated them into an existing one.
     * Drives the "is there organic density yet?" metric; the client just opens
     * `room.code`'s socket either way.
     */
    val created: Boolean,
)

/**
 * One table in the matchmaking chooser. [affordable] is the server-authoritative
 * entry-bar verdict for this caller ([com.dangerfield.cards.server.domain.EntryBar]),
 * so the client never hardcodes the 4× rule: an unaffordable table is still
 * listed (aspirational) but rendered disabled with a "need [minBalanceToSit]
 * chips" label. [minBalanceToSit] is the smallest balance that clears the bar for
 * this table's buy-in.
 */
@Serializable
data class MatchmakingCandidateDto(
    val room: RoomDto,
    val affordable: Boolean,
    val minBalanceToSit: Long,
)

/**
 * GET /v1/matchmaking/candidates response — the qualifying tables a searcher
 * could join for their buy-in range, ordered most-real-humans-first. Powers the
 * chooser flow: the client lists these and the user taps one to join (via the
 * room's socket), instead of being silently auto-seated into the first match.
 * Unlike `find`, unaffordable tables are included (flagged, not filtered) so the
 * chooser can show them disabled. Empty list = nothing in range → the client
 * falls back to the bot-fallback offer.
 */
@Serializable
data class MatchmakingCandidatesResponse(
    val schemaVersion: Int = 1,
    val rooms: List<MatchmakingCandidateDto>,
)

/**
 * GET /v1/matchmaking/subsidy-budget response — the caller's disclosed-bot
 * subsidy draw-down for the current rolling window. The client reads it before
 * offering the bot fallback so a near-cap player is told the limit up front
 * ("you can still play, but winnings won't count toward the daily bonus")
 * instead of discovering it from an unexpected balance afterward (MP-6).
 * [remaining] == 0 means the next bot table is gated.
 */
@Serializable
data class SubsidyBudgetResponse(
    val schemaVersion: Int = 1,
    val grantedToday: Long,
    val cap: Long,
    val remaining: Long,
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
    visibility = visibility.toDto(),
    feltProductId = feltProductId,
    cardBackProductId = cardBackProductId,
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

internal fun com.dangerfield.cards.server.domain.RoomVisibility.toDto(): RoomVisibilityDto = when (this) {
    com.dangerfield.cards.server.domain.RoomVisibility.Private -> RoomVisibilityDto.Private
    com.dangerfield.cards.server.domain.RoomVisibility.Open -> RoomVisibilityDto.Open
    com.dangerfield.cards.server.domain.RoomVisibility.Public -> RoomVisibilityDto.Public
}

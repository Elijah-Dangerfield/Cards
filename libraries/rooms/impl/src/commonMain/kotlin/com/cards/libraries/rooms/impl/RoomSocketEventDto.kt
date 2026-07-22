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

    @Serializable
    @SerialName("emoji_blast")
    data class EmojiBlast(
        val seatIndex: Int,
        val emoji: String,
    ) : RoomSocketEventDto

    @Serializable
    @SerialName("match_over_pending")
    data class MatchOverPending(
        val deadlineEpochMs: Long,
        val bustedSeatIndex: Int,
    ) : RoomSocketEventDto

    @Serializable
    @SerialName("match_over_cleared")
    data object MatchOverCleared : RoomSocketEventDto

    @Serializable
    @SerialName("match_over_resolved")
    data class MatchOverResolved(
        val winnerUserId: String,
    ) : RoomSocketEventDto

    @Serializable
    @SerialName("next_hand_pending")
    data class NextHandPending(
        val deadlineEpochMs: Long,
    ) : RoomSocketEventDto

    @Serializable
    @SerialName("next_hand_cleared")
    data object NextHandCleared : RoomSocketEventDto

    /**
     * Terminal "you can't be seated here" signal: the server-dealt hand left this
     * connected member out solely because their balance fell under the entry bar
     * between finding the table and the deal. Mirrors the server's
     * `SeatUnaffordable`. Without it the search sits on "dealing you in" forever —
     * the member is a real member (the room shows Playing) but holds no seat.
     * [minBalanceToSit] is the chips they'd need to sit at this table's buy-in.
     */
    @Serializable
    @SerialName("seat_unaffordable")
    data class SeatUnaffordable(
        val minBalanceToSit: Long,
    ) : RoomSocketEventDto
}

/**
 * One-line debug summary of an inbound wire frame. Logged at debug-level by
 * [ReconnectingRoomSocket], so it only ships as part of the feedback
 * ring-buffer dump. Keep terse — the buffer is bounded and a busy hand fans
 * out dozens of frames. Omit large payloads (snapshots, decks, hole cards):
 * the client state attachment carries the table state at submit time, and
 * the server log carries the authoritative event payload.
 */
internal fun RoomSocketEventDto.summary(): String = when (this) {
    is RoomSocketEventDto.Snapshot -> "recv snapshot members=${room.members.size}"
    is RoomSocketEventDto.MemberJoined -> "recv member_joined user=${member.userId}"
    is RoomSocketEventDto.MemberLeft -> "recv member_left user=$userId"
    is RoomSocketEventDto.MemberPresenceChanged ->
        "recv presence user=$userId connected=$isConnected"
    RoomSocketEventDto.RoomClosed -> "recv room_closed"
    is RoomSocketEventDto.GameStateSnapshot ->
        "recv game_state hand=${state.handNumber} street=${state.street} " +
            "pot=${state.pots.sumOf { it.amount }} acting=${state.actingSeatIndex} " +
            "seq=${state.lastSequence}"
    is RoomSocketEventDto.GameEventOccurred -> "recv ${event.summary()} seq=$seq"
    is RoomSocketEventDto.IntentAck ->
        "recv intent_ack accepted=$accepted nonce=$clientNonce" +
            (error?.let { " error=$it" } ?: "")
    is RoomSocketEventDto.EmojiBlast -> "recv emoji_blast seat=$seatIndex emoji=$emoji"
    is RoomSocketEventDto.MatchOverPending ->
        "recv match_over_pending busted=$bustedSeatIndex deadline=$deadlineEpochMs"
    RoomSocketEventDto.MatchOverCleared -> "recv match_over_cleared"
    is RoomSocketEventDto.MatchOverResolved -> "recv match_over_resolved winner=$winnerUserId"
    is RoomSocketEventDto.NextHandPending -> "recv next_hand_pending deadline=$deadlineEpochMs"
    RoomSocketEventDto.NextHandCleared -> "recv next_hand_cleared"
    is RoomSocketEventDto.SeatUnaffordable -> "recv seat_unaffordable minBalance=$minBalanceToSit"
}

private fun GameEvent.summary(): String = when (this) {
    is GameEvent.HandStarted -> "HandStarted hand=$handNumber button=$buttonSeatIndex"
    is GameEvent.BlindPosted ->
        "BlindPosted seat=$seatIndex amount=$amount small=$isSmall"
    is GameEvent.HoleCardsDealt -> "HoleCardsDealt seat=$seatIndex"
    is GameEvent.StreetAdvanced ->
        "StreetAdvanced street=$street board=${communityCards.size}"
    is GameEvent.ActionTaken ->
        "ActionTaken seat=$seatIndex action=${action::class.simpleName} " +
            "contribution=$resultingStreetContribution"
    is GameEvent.PotAwarded ->
        "PotAwarded seat=$seatIndex amount=$amount pot=$potIndex"
    is GameEvent.HandEnded -> "HandEnded winners=${winners.size}"
}

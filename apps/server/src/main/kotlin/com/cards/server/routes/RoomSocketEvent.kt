package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
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
 *  - **Lobby + presence**:
 *    - [Snapshot] fires on connect (and after the server applies a
 *      mutation initiated by another client). Carries the full room.
 *      The client treats it as state-of-the-world, last-write-wins.
 *    - [MemberJoined] / [MemberLeft] / [MemberPresenceChanged] are
 *      convenience deltas the UI can use for toasts ("Alice joined")
 *      without diffing snapshots. The Snapshot is still authoritative.
 *    - [RoomClosed] fires when the last member leaves; clients should
 *      drop their connection + return to lobby selection.
 *
 *  - **Game state** (added with server-authoritative multiplayer):
 *    - [GameStateSnapshot] fires after every game mutation. Each
 *      subscriber gets a personalized projection — other seats' hole
 *      cards are scrubbed by `GameState.scrubbedFor(viewerSeatIndex)`
 *      before send. At showdown the server stops scrubbing seats that
 *      went to showdown so the reveal renders correctly.
 *    - [GameEventOccurred] streams `GameEvent`s for animations
 *      (action taken, street advanced, pot awarded, hand ended). The
 *      `HoleCardsDealt` variant is dropped from the wire — the
 *      personalized snapshot already carries the viewer's own cards.
 *    - [IntentAck] closes the loop on a [RoomClientFrame.SubmitIntent]
 *      / [RoomClientFrame.StartHand] / [RoomClientFrame.RequestNextHand].
 *      Sent to the originating socket only; identified by `clientNonce`.
 *      `accepted = false` carries an `error` reason (out-of-turn,
 *      illegal-intent, not-host, etc.).
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

    /**
     * Heads-up match-over lifecycle (MP-14). [MatchOverPending] opens the
     * rebuy-grace window: clients tick a live countdown to [deadlineEpochMs]
     * and prompt the busted seat to rebuy. [MatchOverCleared] fires if the
     * window resolves without a match-over (the busted player rebought).
     * [MatchOverResolved] is terminal — [winnerUserId] took the table, clients
     * route to a result and leave.
     */
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
}

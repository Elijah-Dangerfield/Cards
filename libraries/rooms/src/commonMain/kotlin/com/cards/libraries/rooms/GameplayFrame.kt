package com.dangerfield.cards.libraries.rooms

import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState

/**
 * Server → client frames concerning the in-progress hand, exposed on
 * [RoomConnectionHandle.gameplayFrames]. Mirrors the gameplay subset of
 * the server's `RoomSocketEventDto`:
 *
 *  - [StateSnapshot] — full per-recipient projection of `GameState`
 *    (other seats' hole cards scrubbed server-side).
 *  - [Event] — sequenced `GameEvent` for animation / sound triggers.
 *  - [IntentAck] — ack / reject for a client-submitted [ClientFrame],
 *    correlated by `clientNonce`.
 *  - [EmojiBlast] — a table emote another seat blasted, fanned out to
 *    every socket in the room. Ephemeral (no replay): a late subscriber
 *    must not re-fire a stale reaction.
 *
 * The lobby-shaped concerns (member presence, room snapshot, room
 * closed) flow on [RoomConnectionHandle.connection] instead — the two
 * streams share one underlying WebSocket but split by concern so a
 * gameplay consumer never has to filter lobby noise.
 */
sealed interface GameplayFrame {
    data class StateSnapshot(val state: GameState) : GameplayFrame

    data class Event(
        val seq: Long,
        val event: GameEvent,
    ) : GameplayFrame

    data class IntentAck(
        val clientNonce: String,
        val accepted: Boolean,
        val error: String?,
    ) : GameplayFrame

    data class EmojiBlast(
        val seatIndex: Int,
        val emoji: String,
    ) : GameplayFrame
}

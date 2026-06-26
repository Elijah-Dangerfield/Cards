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
 *  - [MatchOverPending] / [MatchOverCleared] — the heads-up rebuy-grace
 *    countdown opening / clearing (MP-14). The terminal resolve isn't a
 *    gameplay frame: it closes the connection as
 *    [ClosedReason.MatchOver] so the screen routes off, same path as any
 *    other terminal close.
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

    /**
     * The heads-up rebuy-grace window opened: the busted seat has until
     * [deadlineEpochMs] to rebuy or lose the table. Both roles render a live
     * countdown — the busted seat ([bustedSeatIndex]) sees a rebuy CTA, the
     * winner sees "auto-continues in Ns".
     */
    data class MatchOverPending(
        val deadlineEpochMs: Long,
        val bustedSeatIndex: Int,
    ) : GameplayFrame

    /** The grace window cleared without a match-over (the busted seat rebought). */
    data object MatchOverCleared : GameplayFrame
}

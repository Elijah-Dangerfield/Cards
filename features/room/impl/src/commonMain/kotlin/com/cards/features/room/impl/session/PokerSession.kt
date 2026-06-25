package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.features.room.impl.PlayPokerViewModel

import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClosedReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small UI-decoupled session contract scoped to this feature module.
 *
 * The new [PlayPokerViewModel] consumes this instead of [LocalBotsSession] directly so it
 * can be unit-tested with a fake session without instantiating the real bot loop. Tests
 * assert against the engine primitives ([gameStateFlow], [events]) — no UI coupling.
 *
 * `LocalBotsSession` satisfies this via the flows added in commit `3724fc7` plus two
 * one-line method aliases ([submit], [requestNextHand]).
 *
 * **Lifespan:** scoped to this module on purpose. When we extract a real cross-feature
 * `GameSession` abstraction (Phase 4 + MP work, see `:libraries:game`), this internal
 * interface goes away. For now it's the minimum surface the VM needs.
 */
interface PokerSession {

    /** Raw engine state — community cards, pots, seats, whose turn. */
    val gameStateFlow: StateFlow<GameState>

    /** Raw engine events — hand started, action taken, hand ended, etc. Hot flow. */
    val events: SharedFlow<GameEvent>

    /**
     * Table emotes other seats blasted, fanned out by the server. Hot,
     * replay-free flow — a momentary reaction, never re-fired on a late
     * subscribe. Local-bots sessions have no wire and never emit.
     */
    val emoteBlasts: SharedFlow<RemoteEmote>

    /**
     * Connection health. Local sessions stay pinned to [ConnectionState.Connected];
     * remote sessions transition as the underlying socket lifecycle dictates. Drives
     * the play-screen connection banner.
     */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Terminal room-close signal. Emits exactly once when the server tells us the room
     * is gone ([ClosedReason.RoomDeleted]) or refused the subscription
     * ([ClosedReason.Rejected]) — both unrecoverable, so the play screen pops on it
     * instead of spinning on the generic "reconnecting" banner forever. The user-
     * initiated [ClosedReason.Cancelled] case never emits: that close is our own
     * teardown when the player is already leaving.
     *
     * Local-bots sessions can't lose their room, so this never emits for solo.
     */
    val roomClosed: SharedFlow<ClosedReason>

    /**
     * Fires once when the local player becomes the last human at the table —
     * i.e. every other human has left ("all other opponents left"). Distinct
     * from [roomClosed]: the room still exists (the seat may be backfilled, or
     * the player sent off to find another game), so the screen surfaces a
     * notification and routes rather than treating the room as gone. Only fires
     * on the transition from two-or-more humans down to one; a game that *began*
     * with a single human + bots (practice tier) never emits. Local-bots
     * sessions never emit.
     */
    val opponentsLeft: SharedFlow<Unit>

    /**
     * Fires the display name of an opponent who left a *live* table while
     * two or more humans remain — the non-terminal counterpart to
     * [opponentsLeft]. The screen surfaces a transient "X left the table"
     * notice; the seat then renders vacated off the next server snapshot.
     * Never fires when the leaver was the last opponent (that's
     * [opponentsLeft], which routes the player off the dead table) nor for
     * solo-bot sessions. Defaults to a never-emitting flow so the local-bots
     * session and test fakes don't have to override it.
     */
    val opponentLeft: SharedFlow<String> get() = NeverEmits

    /**
     * Fires when the server rejects a [requestNextHand] because the table can't
     * deal another hand — heads-up, the loser busted to 0 and nobody else has
     * chips, so the winner's "next hand" tap would otherwise vanish silently
     * (MP-14). The screen surfaces a notice; the busted player resolves via the
     * rebuy dialog. Defaults to a never-emitting flow so solo-bot sessions and
     * test fakes don't have to override it.
     */
    val nextHandUnavailable: SharedFlow<Unit> get() = NeverEmits

    /**
     * Submit the local player's intent. Suspends because the local-bots implementation
     * runs the bot loop synchronously after the human acts.
     */
    suspend fun submit(intent: PlayerIntent)

    /**
     * Signal readiness to advance to the next hand. No-op when no hand is
     * pending. Remote sessions await the server ack off-band and surface a
     * rejection via [nextHandUnavailable] rather than to the caller, so this
     * stays fire-and-forget.
     */
    fun requestNextHand()

    /**
     * Buy back into the table after busting. Remote sessions send the rebuy
     * frame and **suspend on the server ack** (unlike [requestNextHand]) so the
     * caller learns if the wallet couldn't cover the buy-in — a rejection
     * throws [com.dangerfield.cards.features.room.impl.session.IntentRejectedException].
     * On success the server refills the seat and the next state snapshot
     * carries the restored stack. Local-bots sessions auto-rebuy silently and
     * no-op here.
     */
    suspend fun rebuy()

    /**
     * Leave the room for good. Remote sessions send the durable leave
     * (the HTTP DELETE) so the server frees the seat and broadcasts the
     * departure to the other players; local-bots sessions have no server
     * room and no-op. Best-effort — a failure here only means the seat
     * lingers until the server's grace sweep, never blocks the user's
     * exit, so callers fire it without awaiting a result.
     */
    suspend fun leave()

    /**
     * Blast a table emote to the rest of the room. Fire-and-forget — the
     * sender already renders its own blast locally, so this only carries
     * the emote to opponents. No-op for local-bots sessions.
     */
    suspend fun sendEmote(emoji: String)
}

private val NeverEmits: SharedFlow<Nothing> = MutableSharedFlow()

/**
 * A table emote received from another seat over the wire. [seatIndex]
 * attributes it so the screen can render the blast against that seat's
 * avatar; the VM drops a [seatIndex] resolving to the local human (its
 * own echo) and to a muted seat.
 */
data class RemoteEmote(
    val seatIndex: Int,
    val emoji: String,
)

package com.dangerfield.cards.features.room.impl.session

import com.dangerfield.cards.features.room.impl.PlayPokerViewModel

import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClosedReason
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
     * Terminal room-close signal. Emits exactly once on any unrecoverable close —
     * the room is gone ([ClosedReason.RoomDeleted]), the subscription was refused
     * ([ClosedReason.Rejected]), the socket gave up reconnecting
     * ([ClosedReason.ReconnectFailed]), or the heads-up match resolved
     * ([ClosedReason.MatchOver]) — so the play screen routes off it instead of
     * spinning on the generic "reconnecting" banner forever. MatchOver routes
     * through a result overlay first; the rest pop directly. The user-initiated
     * [ClosedReason.Cancelled] case never emits: that close is our own teardown
     * when the player is already leaving.
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
     * Fires when the server rejects a [requestNextHand], carrying *why* so the
     * screen can react correctly (MP-22). Two refusals look identical on the wire
     * but mean opposite things:
     *
     *  - [NextHandRefusal.CannotDeal] — heads-up, the loser busted to 0 and nobody
     *    else has chips, so the table genuinely can't deal another hand. The winner
     *    waits on the rebuy-grace countdown; surfacing the rebuy notice is correct.
     *  - [NextHandRefusal.Transient] — the hand is still resolving or the tap rode a
     *    stale snapshot (the player backgrounded and their socket flapped). The live
     *    snapshot stream *is* the resync; the screen must NOT show the terminal rebuy
     *    copy, only a quiet "catching up, try again" hint.
     *
     * Defaults to a never-emitting flow so solo-bot sessions and test fakes don't
     * have to override it.
     */
    val nextHandRefused: SharedFlow<NextHandRefusal> get() = NeverRefused

    /**
     * The live heads-up rebuy-grace countdown (MP-14), or null when no match-over
     * is pending. Set when the server opens the grace window, cleared when the
     * busted player rebuys (the table resumes) or the match resolves (a terminal
     * close routes the screen off). Both roles render a countdown to
     * [MatchOverCountdown.deadlineEpochMs]; the busted role
     * ([MatchOverCountdown.localPlayerIsBusted]) also gets a rebuy CTA. Defaults
     * to a constant-null flow so solo sessions and test fakes don't override it.
     */
    val matchOverCountdown: StateFlow<MatchOverCountdown?> get() = NoMatchOver

    /**
     * Submit the local player's intent. Suspends because the local-bots implementation
     * runs the bot loop synchronously after the human acts.
     */
    suspend fun submit(intent: PlayerIntent)

    /**
     * Signal readiness to advance to the next hand. No-op when no hand is
     * pending. Remote sessions await the server ack off-band and surface a
     * rejection via [nextHandRefused] rather than to the caller, so this
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
private val NeverRefused: SharedFlow<NextHandRefusal> = MutableSharedFlow()
private val NoMatchOver: StateFlow<MatchOverCountdown?> = MutableStateFlow(null)

/**
 * Why the server refused a [PokerSession.requestNextHand] (MP-22). Splits the one
 * genuine "the table can't deal another hand" case from every transient race so
 * the screen never shows the terminal rebuy copy for a tap that just needs a
 * resync.
 */
enum class NextHandRefusal {
    /**
     * Heads-up bust with no rebuy yet — the table truly can't deal. The winner
     * waits on the rebuy-grace countdown; the rebuy notice is the right surface.
     */
    CannotDeal,

    /**
     * The hand is still resolving or the request rode a stale snapshot (a flapping
     * socket after backgrounding). Not terminal — the live snapshot stream is the
     * resync. The screen shows a quiet "catching up, try again" hint, never the
     * opponent-rebuy copy.
     */
    Transient,
}

/**
 * The live state of a heads-up rebuy-grace window (MP-14). [deadlineEpochMs] is
 * when the window expires; the screen ticks a countdown against the clock to it.
 * [localPlayerIsBusted] splits the role: the busted player sees "rebuy in Ns or
 * lose your seat" + a rebuy CTA, the winner sees "auto-continues in Ns".
 */
data class MatchOverCountdown(
    val deadlineEpochMs: Long,
    val localPlayerIsBusted: Boolean,
)

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

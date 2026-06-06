package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.rooms.ClosedReason
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
     * Submit the local player's intent. Suspends because the local-bots implementation
     * runs the bot loop synchronously after the human acts.
     */
    suspend fun submit(intent: PlayerIntent)

    /** Signal readiness to advance to the next hand. No-op when no hand is pending. */
    fun requestNextHand()
}

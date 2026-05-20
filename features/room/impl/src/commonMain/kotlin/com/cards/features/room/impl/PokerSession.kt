package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.game.ConnectionState
import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
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
     * Submit the local player's intent. Suspends because the local-bots implementation
     * runs the bot loop synchronously after the human acts.
     */
    suspend fun submit(intent: PlayerIntent)

    /** Signal readiness to advance to the next hand. No-op when no hand is pending. */
    fun requestNextHand()
}

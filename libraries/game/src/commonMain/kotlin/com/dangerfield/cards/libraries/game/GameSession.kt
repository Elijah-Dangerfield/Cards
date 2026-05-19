package com.dangerfield.cards.libraries.game

import com.dangerfield.cards.libraries.gameplay.GameEvent
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A live, stateful, event-emitting session of one poker game.
 *
 * The contract that lets the same screen play solo against bots OR multiplayer against
 * humans, without knowing which.
 *
 * Implementations:
 * - `LocalGameSession` — owns a local `GameEngine` and drives bot decisions in-process
 * - `RemoteGameSession` (Phase 4) — mirrors a server-driven game over WebSocket
 *
 * The UI subscribes to [state], [events], and [occupants]. It submits player actions via
 * [submit]. It does not branch on session type.
 */
interface GameSession {

    /**
     * Current game state — community cards, pots, seat stacks, whose turn it is.
     *
     * Reflects the canonical game-engine state (locally computed for `LocalGameSession`,
     * server-mirrored for `RemoteGameSession`).
     */
    val state: StateFlow<GameState>

    /**
     * Events as they happen — hand started, action taken, pot awarded, hand ended.
     *
     * Hot flow. Late subscribers do not get historical events; the [state] flow is the
     * source of truth for "what's currently true." Use [events] for transient signals
     * (animations, achievement triggers, sound cues, telemetry).
     */
    val events: SharedFlow<GameEvent>

    /**
     * Who is sitting at each seat — bots, humans, or empty. Aligned with [GameState.seats]
     * by `seatIndex`. Updates when players join, leave, or are replaced (e.g.,
     * disconnect → reconnect-as-bot transition).
     */
    val occupants: StateFlow<List<SeatOccupant>>

    /** Connection health. Always [ConnectionState.Connected] for local sessions. */
    val connectionState: StateFlow<ConnectionState>

    /**
     * Submit the local player's intent to act. For local sessions, this hands the intent
     * directly to the in-process `GameEngine`. For remote sessions, it's serialized and
     * sent over the WebSocket.
     *
     * Validation (is it actually this player's turn? is the bet size legal?) happens on
     * the authoritative side — locally for solo, server-side for MP.
     */
    fun submit(intent: PlayerIntent)

    /**
     * Advance to the next hand. Only meaningful when the current hand has ended (see
     * [GameEvent.HandEnded]). No-op otherwise.
     *
     * In MP, this is typically auto-triggered by the server after a short delay; the
     * client call is a signal of readiness, not a command.
     */
    fun requestNextHand()

    /**
     * Tear down the session. Cancels coroutine scope, closes WebSocket if any, releases
     * any held resources. Idempotent.
     */
    fun close()
}

/**
 * Connection health for a session. `LocalGameSession` is always [Connected].
 * `RemoteGameSession` transitions through these states as the WebSocket lifecycle
 * dictates.
 */
enum class ConnectionState {
    Connected,
    Reconnecting,
    Disconnected,
}

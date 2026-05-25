package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.di.ServerScope
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.concurrent.ConcurrentHashMap

/**
 * One [GameSession] per active room. Lifecycle:
 *
 *  - [startHand] creates (or reuses) a session for the room and runs
 *    the engine's `startHand` under the session's mutex. The room
 *    transition to `RoomStatus.Playing` is the caller's job (lives in
 *    `InMemoryRoomService`).
 *  - [applyIntent] / [requestNextHand] proxy into the session.
 *  - [peek] returns the session or null — used by the socket publisher
 *    to decide whether a viewer subscribes to game frames.
 *  - [end] drops the session when the room closes or finishes.
 *
 * Threading: `ConcurrentHashMap` covers the registry's
 * register/lookup contention. Per-session mutation is serialized by a
 * `Mutex` inside [GameSession] itself. The two layers handle different
 * concerns — don't merge into one lock.
 *
 * Bots: the registry doesn't know or care about bots; they're just
 * another `SeatOccupant` with `isBot = true`. Phase 3 adds a server
 * coroutine that observes [GameSession.state] and submits intents on
 * the bot's behalf via [applyIntent] with a `"bot-..."` actorUserId.
 */
interface GameSessionRegistry {
    suspend fun startHand(
        code: String,
        occupants: List<SeatOccupant>,
        settings: RoomSettings,
    ): IntentResult

    suspend fun applyIntent(
        code: String,
        actorUserId: String,
        intent: PlayerIntent,
        clientNonce: String,
    ): IntentResult

    suspend fun requestNextHand(
        code: String,
        actorUserId: String,
        clientNonce: String,
    ): IntentResult

    /**
     * Returns the active session for [code] or null if the room has no
     * game session yet. Used by the socket publisher to gate game-frame
     * subscription.
     */
    fun peek(code: String): GameSession?

    /**
     * Drop the session for [code]. Called when the room closes or the
     * last player leaves. Does nothing if no session exists.
     */
    fun end(code: String)
}

@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class InMemoryGameSessionRegistry : GameSessionRegistry {
    private val sessions = ConcurrentHashMap<String, GameSession>()

    override suspend fun startHand(
        code: String,
        occupants: List<SeatOccupant>,
        settings: RoomSettings,
    ): IntentResult = sessionFor(code).startHand(occupants, settings)

    override suspend fun applyIntent(
        code: String,
        actorUserId: String,
        intent: PlayerIntent,
        clientNonce: String,
    ): IntentResult = sessions[code]
        ?.applyIntent(actorUserId, intent, clientNonce)
        ?: IntentResult.Rejected("no game session for room $code")

    override suspend fun requestNextHand(
        code: String,
        actorUserId: String,
        clientNonce: String,
    ): IntentResult = sessions[code]
        ?.requestNextHand(actorUserId, clientNonce)
        ?: IntentResult.Rejected("no game session for room $code")

    override fun peek(code: String): GameSession? = sessions[code]

    override fun end(code: String) {
        sessions.remove(code)
    }

    /**
     * Atomically returns the existing session for [code] or creates +
     * registers a new one. `computeIfAbsent` keeps "first writer wins"
     * semantics so two concurrent `startHand` calls on the same code
     * land on the same session and serialize through its mutex.
     */
    private fun sessionFor(code: String): GameSession =
        sessions.computeIfAbsent(code) { GameSession() }
}

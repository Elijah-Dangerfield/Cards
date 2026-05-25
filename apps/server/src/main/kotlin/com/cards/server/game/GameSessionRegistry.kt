package com.dangerfield.cards.server.game

import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import com.dangerfield.cards.server.di.ServerScope
import java.util.concurrent.ConcurrentHashMap

/**
 * One [GameSession] per active room. Phase 1 ships this as a skeleton —
 * it exposes only [peek] for callers that need to know whether a room
 * currently has an active game (e.g. the socket publisher in Phase 2a
 * deciding whether to subscribe a viewer to game frames or just to
 * lobby frames).
 *
 * Phase 2a fleshes this out with:
 *  - `start(code, occupants, settings)` — create + register a session,
 *    return the initial `GameState`.
 *  - `applyIntent(code, actorUserId, intent, nonce)` — proxy into the
 *    session's mutex-guarded engine call.
 *  - `requestNextHand(code, actorUserId, nonce)` — proxy.
 *  - `end(code)` — drop the session when the room closes or finishes.
 *  - `observe(code): Flow<GameState>?` — used by the socket publisher.
 *
 * Threading: `ConcurrentHashMap` covers the registry's own
 * register/lookup contention. Per-session mutation is serialized by a
 * `Mutex` inside [GameSession] itself (Phase 2). The two layers handle
 * different concerns — don't merge them into one lock.
 */
interface GameSessionRegistry {
    /**
     * Returns the active session for [code] or null if the room has no
     * active game (lobby-only). Used by the socket publisher to gate
     * game-frame subscription.
     */
    fun peek(code: String): GameSession?
}

@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
class InMemoryGameSessionRegistry : GameSessionRegistry {
    private val sessions = ConcurrentHashMap<String, GameSession>()

    override fun peek(code: String): GameSession? = sessions[code]
}

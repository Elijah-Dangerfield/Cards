package com.dangerfield.cards.server.game

/**
 * Server-side wrapper around a single room's in-progress poker hand.
 *
 * Phase 1 ships this as a skeleton — it carries no state and exposes no
 * methods. Phase 2 fleshes it out with the real shape:
 *
 *  - holds `GameState` from `libraries/gameplay`
 *  - guards mutations with a per-room `Mutex`
 *  - exposes `StateFlow<GameState>` + `SharedFlow<GameEvent>` for the
 *    socket publisher to fan out
 *  - implements `start(occupants)`, `applyIntent(actorUserId, intent, nonce)`,
 *    `requestNextHand(actorUserId, nonce)`
 *
 * Keeping the type defined now lets `GameSessionRegistry` reference it
 * without forcing Phase 1 to commit to a method shape that Phase 2
 * might revise.
 */
class GameSession internal constructor()

package com.dangerfield.cards.libraries.game

/**
 * Creates the right [GameSession] implementation for a given [PlayMode].
 *
 * Injected into the play-screen ViewModel via DI. The ViewModel calls [create] with the
 * mode it received as a navigation argument and gets back a session it can subscribe to,
 * without knowing or caring which concrete implementation it received.
 *
 * Routing:
 * - [PlayMode.SoloVsBots] → `LocalGameSession`
 * - [PlayMode.FriendGame], [PlayMode.PublicGame] → `RemoteGameSession` (Phase 4)
 */
interface GameSessionFactory {
    fun create(mode: PlayMode): GameSession
}

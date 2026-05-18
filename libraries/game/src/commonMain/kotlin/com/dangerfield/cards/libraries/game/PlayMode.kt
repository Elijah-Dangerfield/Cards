package com.dangerfield.cards.libraries.game

import kotlinx.serialization.Serializable

/**
 * The mode a play session is opened in. Passed as a navigation argument to the play
 * screen; consumed by [GameSessionFactory] to construct the right session implementation.
 *
 * - [SoloVsBots] → `LocalGameSession`
 * - [FriendGame], [PublicGame] → `RemoteGameSession` (Phase 4)
 *
 * Serializable because it travels as a navigation argument.
 */
@Serializable
sealed interface PlayMode {

    /**
     * Local-only solo play against bots. No server involvement.
     *
     * @param difficultyName the bot difficulty tier name (Casual / Standard / Challenging).
     *   String rather than enum to avoid pulling `libraries/bots/` as an API dependency.
     * @param personalityKeys identifiers of the bot personalities to use. Resolved to
     *   `BotPersonality` instances by the local session implementation.
     */
    @Serializable
    data class SoloVsBots(
        val difficultyName: String,
        val personalityKeys: List<String>,
    ) : PlayMode

    /**
     * Private multiplayer room joined via a 6-character code or deep link.
     *
     * @param roomCode the share code (e.g., "QTACE7").
     */
    @Serializable
    data class FriendGame(
        val roomCode: String,
    ) : PlayMode

    /**
     * Public multiplayer room found via Quick Match or Browse.
     *
     * @param stakeTierName e.g., "Practice", "Casual", "Standard", "High", "Premium".
     * @param roomId the matched-into room identifier (server-assigned).
     */
    @Serializable
    data class PublicGame(
        val stakeTierName: String,
        val roomId: String,
    ) : PlayMode
}

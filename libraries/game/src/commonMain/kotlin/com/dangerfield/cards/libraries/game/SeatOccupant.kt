package com.dangerfield.cards.libraries.game

/**
 * Who is sitting at a seat. Layered *on top of* the game-engine's `Seat` primitive
 * (which carries gameplay state — stack, hole cards, contributed-this-street). This type
 * carries presentation and social state for the tap-avatar surface, friend operations,
 * and achievement triggers.
 *
 * Sealed so UI code can type-narrow only for the operations that actually differ between
 * bots and humans. For pure rendering (avatar, name, current bet, stack), no branch is
 * needed — read the shared fields.
 */
sealed interface SeatOccupant {
    val seatIndex: Int
    val displayName: String
    val personality: Personality?

    /**
     * A bot seat. Always has a personality (fixed at construction from the chosen
     * `BotPersonality` in `libraries/bots/`). The bot's decision logic is the same code
     * regardless of context — local for solo play, server-side for MP bot fill.
     */
    data class Bot(
        override val seatIndex: Int,
        override val displayName: String,
        override val personality: Personality,
    ) : SeatOccupant

    /**
     * A human seat. Personality is null until enough hand history accumulates to compute
     * a meaningful heat map (~50 shared hands, per product spec §6.4). New humans show
     * with [Personality.style] = [PlayStyle.Unknown] or null personality entirely.
     */
    data class Human(
        override val seatIndex: Int,
        override val displayName: String,
        val userId: String,
        override val personality: Personality?,
        val level: Int,
        val leagueTier: String?, // null = unranked or solo-only player
    ) : SeatOccupant

    /**
     * An empty seat. Has no display name or personality. Renders as a join slot or
     * placeholder depending on context.
     */
    data class Empty(
        override val seatIndex: Int,
    ) : SeatOccupant {
        override val displayName: String get() = ""
        override val personality: Personality? get() = null
    }
}

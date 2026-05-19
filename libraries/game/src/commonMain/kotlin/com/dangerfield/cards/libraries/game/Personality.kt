package com.dangerfield.cards.libraries.game

/**
 * A player's poker personality — the data the tap-avatar surface and play-style heat map
 * render. Shared between bots and humans by design.
 *
 * - For bots: populated deterministically at construction from the chosen
 *   `BotPersonality` (in `libraries/bots/`).
 * - For humans: populated from accumulated hand history (~50 shared hands per product
 *   spec §6.4). Until enough data accumulates, [style] = [PlayStyle.Unknown] and metrics
 *   are null.
 */
data class Personality(
    /**
     * Human-readable label. For bots, typically the bot's name ("Steve"). For humans,
     * typically the derived style ("Tight Aggressive"). UI decides which to render based
     * on `SeatOccupant` type.
     */
    val label: String,

    /** Categorical style classification. */
    val style: PlayStyle,

    /**
     * Voluntarily Put $ In Pot — fraction of hands the player voluntarily entered.
     * Range 0.0–1.0. Null when insufficient data.
     */
    val vpip: Double? = null,

    /**
     * Pre-Flop Raise — fraction of hands the player raised pre-flop.
     * Range 0.0–1.0. Null when insufficient data.
     */
    val pfr: Double? = null,

    /**
     * Aggression Factor — (bets + raises) / calls. Higher = more aggressive.
     * Null when insufficient data.
     */
    val aggressionFactor: Double? = null,
)

enum class PlayStyle {
    TightAggressive,
    TightPassive,
    LooseAggressive,
    LoosePassive,

    /** For humans before heat-map data accumulates. Bots never have this style. */
    Unknown,
}

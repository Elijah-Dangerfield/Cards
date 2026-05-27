package com.dangerfield.cards.features.room.impl

import com.dangerfield.cards.libraries.bots.BotPersonality

/**
 * Derived archetype + one-line description for a bot personality. The
 * tap-an-opponent profile sheet renders these so the user can read the
 * bot's tendencies at a glance — "loose-aggressive: bets a lot, will
 * push on weakness" is useful gameplay information, the raw tightness /
 * aggression / bluffRate numbers are not.
 *
 * Bucketing intentionally uses wide bands so the same archetype reads
 * for adjacent personalities — a near-balanced bot doesn't pop in and
 * out of an archetype label across slightly different parameter tunes.
 */
internal data class BotPlayingStyle(
    val label: String,
    val description: String,
)

internal fun playingStyleFor(personality: BotPersonality): BotPlayingStyle {
    val loose = personality.tightness < 0.50
    val tight = personality.tightness > 0.55
    val aggressive = personality.aggression > 0.55
    val passive = personality.aggression < 0.45
    val maniac = personality.bluffRate >= 0.25 && aggressive && loose

    return when {
        maniac -> BotPlayingStyle(
            label = "Maniac",
            description = "Plays nearly every hand and bets big. Bluffs often — hard to fold to, but hard to trust.",
        )
        loose && aggressive -> BotPlayingStyle(
            label = "Loose aggressive",
            description = "Plays a wide range and bets confidently. Will push hard when he senses weakness.",
        )
        tight && aggressive -> BotPlayingStyle(
            label = "Tight aggressive",
            description = "Patient, but when chips go in, expect a real hand.",
        )
        tight && passive -> BotPlayingStyle(
            label = "Tight passive",
            description = "Folds often, rarely raises. A big bet from this seat usually means the goods.",
        )
        loose && passive -> BotPlayingStyle(
            label = "Loose passive",
            description = "Calls a lot, rarely raises. Hard to push off marginal hands — value bet, don't bluff.",
        )
        else -> BotPlayingStyle(
            label = "Balanced",
            description = "Mixes it up. No single tendency to exploit — read each hand.",
        )
    }
}

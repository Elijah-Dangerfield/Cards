package com.dangerfield.cards.libraries.bots

/**
 * Coarse, display-facing classification of a [BotPersonality]'s tendencies.
 *
 * Pure bucketing shared by the client (which maps an archetype to localized
 * strings + the profile-sheet radar) and the server (which maps it to a wire
 * label on the lobby member DTO so a revealed bot can advertise its style).
 *
 * Bands are intentionally wide so adjacent personalities read as the same
 * archetype rather than popping between labels across small parameter tunes.
 */
enum class BotArchetype {
    Maniac,
    LooseAggressive,
    TightAggressive,
    TightPassive,
    LoosePassive,
    Balanced,
}

fun archetypeFor(personality: BotPersonality): BotArchetype {
    val loose = personality.tightness < 0.50
    val tight = personality.tightness > 0.55
    val aggressive = personality.aggression > 0.55
    val passive = personality.aggression < 0.45
    val maniac = personality.bluffRate >= 0.25 && aggressive && loose

    return when {
        maniac -> BotArchetype.Maniac
        loose && aggressive -> BotArchetype.LooseAggressive
        tight && aggressive -> BotArchetype.TightAggressive
        tight && passive -> BotArchetype.TightPassive
        loose && passive -> BotArchetype.LoosePassive
        else -> BotArchetype.Balanced
    }
}

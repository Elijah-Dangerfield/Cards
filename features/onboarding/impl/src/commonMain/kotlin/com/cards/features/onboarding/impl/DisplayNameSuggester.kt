package com.dangerfield.cards.features.onboarding.impl

import kotlin.random.Random

/**
 * Fallback-only display name generator. Yields names like "QuietAce72",
 * "BoldKing41". Used on the PickIdentity step when the server-issued
 * profile name hasn't arrived in time.
 */
internal object DisplayNameSuggester {
    private val adjectives = listOf(
        "Quiet", "Bold", "Sharp", "Lucky", "Steady", "Calm", "Wild",
        "Quick", "Stoic", "Bright", "Cool", "Slick", "Brave", "Sly",
    )

    private val ranks = listOf(
        "Ace", "King", "Queen", "Jack", "Ten", "Nine", "Eight", "Joker",
    )

    fun next(random: Random = Random): String {
        val adj = adjectives.random(random)
        val rank = ranks.random(random)
        val n = random.nextInt(10, 100)
        return "$adj$rank$n"
    }
}

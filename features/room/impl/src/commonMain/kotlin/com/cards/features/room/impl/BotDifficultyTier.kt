package com.dangerfield.cards.features.room.impl

/**
 * Derived blurb for the table-level bot difficulty. Surfaced on the
 * tap-an-opponent profile sheet alongside the per-bot playing style:
 * the style says how this individual bot plays, the tier says how the
 * whole table is tuned. The two together tell the user what to expect
 * before they look at the seat across from them.
 *
 * Labels come from [SoloBotsPokerSessionFactory.difficultyName] — the
 * three home-screen entry points (Casual / Standard / Challenging).
 * Unknown labels (e.g. future MP tables that lack a difficulty
 * concept) fall back to null and the sheet omits the section.
 */
internal data class BotDifficultyTier(
    val label: String,
    val description: String,
)

internal fun difficultyTierFor(label: String): BotDifficultyTier? = when (label) {
    "Casual" -> BotDifficultyTier(
        label = "Casual",
        description = "Forgiving table. Bots play loosely and rarely punish a thin call — a good place to find your footing.",
    )
    "Standard" -> BotDifficultyTier(
        label = "Standard",
        description = "Balanced challenge. Bots punish loose play and exploit obvious tells — the default test of your read.",
    )
    "Challenging" -> BotDifficultyTier(
        label = "Challenging",
        description = "Sharp table. Bots pressure marginal hands and read your patterns — bring your A game.",
    )
    else -> null
}

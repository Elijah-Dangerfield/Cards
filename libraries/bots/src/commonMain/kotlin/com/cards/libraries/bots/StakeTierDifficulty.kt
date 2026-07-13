package com.dangerfield.cards.libraries.bots

import com.dangerfield.cards.libraries.gameplay.StakeTier

/**
 * The bot difficulty a table's stake tier should field. Low stakes play soft
 * (Casual), the middle plays straight (Standard), and the high tiers bring the
 * sharp bots (Challenging) — so a Premium table isn't stocked with the same
 * opponents as a Casual one. The inverse of solo's difficulty-to-tier mapping.
 */
fun StakeTier.toBotDifficulty(): BotDifficulty = when (this) {
    StakeTier.Practice, StakeTier.Casual -> BotDifficulty.Casual
    StakeTier.Standard -> BotDifficulty.Standard
    StakeTier.High, StakeTier.Premium -> BotDifficulty.Challenging
}

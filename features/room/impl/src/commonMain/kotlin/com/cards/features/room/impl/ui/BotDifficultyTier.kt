package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.features.room.impl.session.SoloBotsPokerSessionFactory

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_player_profile_difficulty_casual_description
import cards.libraries.resources.generated.resources.room_player_profile_difficulty_challenging_description
import cards.libraries.resources.generated.resources.room_player_profile_difficulty_standard_description
import org.jetbrains.compose.resources.StringResource

/**
 * Derived blurb for the table-level bot difficulty. Surfaced on the
 * tap-an-opponent profile sheet alongside the per-bot playing style:
 * the style says how this individual bot plays, the tier says how the
 * whole table is tuned. The two together tell the user what to expect
 * before they look at the seat across from them.
 *
 * `label` is the wire-key Casual / Standard / Challenging value from
 * [SoloBotsPokerSessionFactory.difficultyName] — kept as `String`
 * because it doubles as the routing key downstream (see the
 * `OptionPillRow` deferral noted in `home_bot_setup_*` migration).
 * Unknown labels (e.g. future MP tables that lack a difficulty
 * concept) fall back to null and the sheet omits the section.
 */
internal data class BotDifficultyTier(
    val label: String,
    val description: StringResource,
)

internal fun difficultyTierFor(label: String): BotDifficultyTier? = when (label) {
    "Casual" -> BotDifficultyTier(
        label = "Casual",
        description = Res.string.room_player_profile_difficulty_casual_description,
    )
    "Standard" -> BotDifficultyTier(
        label = "Standard",
        description = Res.string.room_player_profile_difficulty_standard_description,
    )
    "Challenging" -> BotDifficultyTier(
        label = "Challenging",
        description = Res.string.room_player_profile_difficulty_challenging_description,
    )
    else -> null
}

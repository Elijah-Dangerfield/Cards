package com.dangerfield.cards.features.room.impl

import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_player_profile_style_balanced_description
import cards.libraries.resources.generated.resources.room_player_profile_style_balanced_label
import cards.libraries.resources.generated.resources.room_player_profile_style_loose_aggressive_description
import cards.libraries.resources.generated.resources.room_player_profile_style_loose_aggressive_label
import cards.libraries.resources.generated.resources.room_player_profile_style_loose_passive_description
import cards.libraries.resources.generated.resources.room_player_profile_style_loose_passive_label
import cards.libraries.resources.generated.resources.room_player_profile_style_maniac_description
import cards.libraries.resources.generated.resources.room_player_profile_style_maniac_label
import cards.libraries.resources.generated.resources.room_player_profile_style_tight_aggressive_description
import cards.libraries.resources.generated.resources.room_player_profile_style_tight_aggressive_label
import cards.libraries.resources.generated.resources.room_player_profile_style_tight_passive_description
import cards.libraries.resources.generated.resources.room_player_profile_style_tight_passive_label
import com.dangerfield.cards.libraries.bots.BotArchetype
import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.archetypeFor
import com.dangerfield.cards.libraries.ui.components.RadarAxis
import org.jetbrains.compose.resources.StringResource

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
    val label: StringResource,
    val description: StringResource,
)

internal fun playingStyleFor(personality: BotPersonality): BotPlayingStyle =
    when (archetypeFor(personality)) {
        BotArchetype.Maniac -> BotPlayingStyle(
            label = Res.string.room_player_profile_style_maniac_label,
            description = Res.string.room_player_profile_style_maniac_description,
        )
        BotArchetype.LooseAggressive -> BotPlayingStyle(
            label = Res.string.room_player_profile_style_loose_aggressive_label,
            description = Res.string.room_player_profile_style_loose_aggressive_description,
        )
        BotArchetype.TightAggressive -> BotPlayingStyle(
            label = Res.string.room_player_profile_style_tight_aggressive_label,
            description = Res.string.room_player_profile_style_tight_aggressive_description,
        )
        BotArchetype.TightPassive -> BotPlayingStyle(
            label = Res.string.room_player_profile_style_tight_passive_label,
            description = Res.string.room_player_profile_style_tight_passive_description,
        )
        BotArchetype.LoosePassive -> BotPlayingStyle(
            label = Res.string.room_player_profile_style_loose_passive_label,
            description = Res.string.room_player_profile_style_loose_passive_description,
        )
        BotArchetype.Balanced -> BotPlayingStyle(
            label = Res.string.room_player_profile_style_balanced_label,
            description = Res.string.room_player_profile_style_balanced_description,
        )
    }

/**
 * Four axes that visually distinguish each personality on the profile-sheet
 * radar. Patience is the explicit inverse of aggression so the polygon
 * doesn't collapse to a triangle on bots that score near zero on aggression;
 * bluffRate is rescaled to 0..1 from its 0..0.4 source range so it occupies
 * the same visual space as the other axes.
 */
internal fun radarAxesFor(personality: BotPersonality): List<RadarAxis> = listOf(
    RadarAxis(label = "Tight", value = personality.tightness.toFloat()),
    RadarAxis(label = "Aggro", value = personality.aggression.toFloat()),
    RadarAxis(label = "Bluff", value = (personality.bluffRate / 0.4).toFloat().coerceIn(0f, 1f)),
    RadarAxis(label = "Patient", value = (1.0 - personality.aggression).toFloat()),
)

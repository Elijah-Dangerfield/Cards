package com.dangerfield.cards.libraries.ui.components

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
import com.dangerfield.cards.libraries.bots.archetypeFor
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import org.jetbrains.compose.resources.StringResource

/**
 * Archetype label + one-line description for a play-style. Shared so a human's
 * derived style and a bot's read as the same vocabulary across the Stats page,
 * the profile teaser, and the seat-tap player card.
 */
data class PlayStyleCopy(val label: StringResource, val description: StringResource)

/** The four human axes as the radar's labelled axes (Tight / Aggro / Bluff / Patient). */
fun PlayStyleAxes.toRadarAxes(): List<RadarAxis> = listOf(
    RadarAxis(label = "Tight", value = tight),
    RadarAxis(label = "Aggro", value = aggro),
    RadarAxis(label = "Bluff", value = bluff),
    RadarAxis(label = "Patient", value = patient),
)

/** Classify these axes into the archetype label + description copy. */
fun PlayStyleAxes.toStyleCopy(): PlayStyleCopy = archetypeFor(
    tightness = tight.toDouble(),
    aggression = aggro.toDouble(),
    // The bluff axis is a 0..1 showdown-bluff rate; the archetype classifier
    // expects the bot's 0..0.4 bluffRate scale, so rescale it.
    bluffRate = (bluff * 0.4f).toDouble(),
).toPlayStyleCopy()

/** Map an archetype to its display copy — the single source for these strings. */
fun BotArchetype.toPlayStyleCopy(): PlayStyleCopy = when (this) {
    BotArchetype.Maniac -> PlayStyleCopy(
        label = Res.string.room_player_profile_style_maniac_label,
        description = Res.string.room_player_profile_style_maniac_description,
    )
    BotArchetype.LooseAggressive -> PlayStyleCopy(
        label = Res.string.room_player_profile_style_loose_aggressive_label,
        description = Res.string.room_player_profile_style_loose_aggressive_description,
    )
    BotArchetype.TightAggressive -> PlayStyleCopy(
        label = Res.string.room_player_profile_style_tight_aggressive_label,
        description = Res.string.room_player_profile_style_tight_aggressive_description,
    )
    BotArchetype.TightPassive -> PlayStyleCopy(
        label = Res.string.room_player_profile_style_tight_passive_label,
        description = Res.string.room_player_profile_style_tight_passive_description,
    )
    BotArchetype.LoosePassive -> PlayStyleCopy(
        label = Res.string.room_player_profile_style_loose_passive_label,
        description = Res.string.room_player_profile_style_loose_passive_description,
    )
    BotArchetype.Balanced -> PlayStyleCopy(
        label = Res.string.room_player_profile_style_balanced_label,
        description = Res.string.room_player_profile_style_balanced_description,
    )
}

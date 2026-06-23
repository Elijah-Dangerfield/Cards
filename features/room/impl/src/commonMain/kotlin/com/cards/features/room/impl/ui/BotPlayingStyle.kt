package com.dangerfield.cards.features.room.impl.ui

import com.dangerfield.cards.libraries.bots.BotPersonality
import com.dangerfield.cards.libraries.bots.archetypeFor
import com.dangerfield.cards.libraries.ui.components.PlayStyleCopy
import com.dangerfield.cards.libraries.ui.components.RadarAxis
import com.dangerfield.cards.libraries.ui.components.toPlayStyleCopy

/**
 * Derived archetype label + one-line description for a bot personality. The
 * tap-an-opponent profile sheet renders these so the user can read the bot's
 * tendencies at a glance — "loose-aggressive: bets a lot, will push on
 * weakness" is useful, the raw tightness / aggression / bluffRate numbers
 * aren't.
 *
 * Delegates to the shared [toPlayStyleCopy] so a bot and a human's derived
 * style read as the same vocabulary (see :libraries:ui PlayStyleCopy).
 */
internal fun playingStyleFor(personality: BotPersonality): PlayStyleCopy =
    archetypeFor(personality).toPlayStyleCopy()

/**
 * Four axes that visually distinguish each personality on the profile-sheet
 * radar. Patience is the explicit inverse of aggression so the polygon doesn't
 * collapse to a triangle on bots that score near zero on aggression; bluffRate
 * is rescaled to 0..1 from its 0..0.4 source range so it occupies the same
 * visual space as the other axes.
 */
internal fun radarAxesFor(personality: BotPersonality): List<RadarAxis> = listOf(
    RadarAxis(label = "Tight", value = personality.tightness.toFloat()),
    RadarAxis(label = "Aggro", value = personality.aggression.toFloat()),
    RadarAxis(label = "Bluff", value = (personality.bluffRate / 0.4).toFloat().coerceIn(0f, 1f)),
    RadarAxis(label = "Patient", value = (1.0 - personality.aggression).toFloat()),
)

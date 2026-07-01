package com.dangerfield.cards.libraries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Punches a badge out of whatever sits behind it: a ring of [ringColor] (set to
 * the exact surrounding colour — the felt, a card's surface, the page
 * background) around a [fillColor] interior. When the ring matches the backdrop
 * the badge reads as cleanly cut out of it; where it overlaps something else
 * (an avatar, a gold ring) the ring separates the two.
 *
 * Renders the ring as a real outer fill plus an inset inner fill — NOT a border
 * stroke over a background. A border stroke is centred on the shape path, so the
 * rounded background's anti-aliased edge bleeds a hairline past it and shows a
 * stray light ring (the bug this replaces). Here the outermost pixels are
 * [ringColor], so there's no stray edge.
 *
 * Apply any fixed `size` BEFORE this; apply content `padding` AFTER it.
 */
fun Modifier.cutout(
    ringColor: Color,
    fillColor: Color,
    shape: Shape,
    ringWidth: Dp = 2.dp,
): Modifier = this
    .clip(shape)
    .background(ringColor)
    .padding(ringWidth)
    .clip(shape)
    .background(fillColor)

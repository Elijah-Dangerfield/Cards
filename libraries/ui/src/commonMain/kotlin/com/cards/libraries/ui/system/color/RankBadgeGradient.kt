package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.ui.graphics.Brush

/**
 * Purple-to-pink linear gradient used for the rank disc on every surface
 * that surfaces the player's multiplayer Elo rating — the `RankBadge`
 * primitive shown on Profile and the `RankHero` disc inside
 * `RankDetailSheet`. Centralised here so "the rank affordance looks the
 * same everywhere" stays true under future palette tweaks.
 *
 * Not a theme-driven token: these are brand-specific swatches tied to
 * the visual identity of the rank system, the same way [PokerPalette]'s
 * chip-gold is tied to a physical chip. They render identically under
 * any future light/dark theme.
 */
val RankBadgeGradient: Brush = Brush.linearGradient(
    listOf(ColorResource.PokerRankBadgePurple.color, ColorResource.PokerRankBadgePink.color),
)

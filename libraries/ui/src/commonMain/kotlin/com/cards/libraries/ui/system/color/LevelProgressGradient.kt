package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.ui.graphics.Brush

/**
 * Cyan-to-green linear gradient used for every surface that visualises
 * level / XP progress — level pill on Profile + Stats, level circle on
 * Stats, XP-earned chip on the hand-result dialog. Centralised here so
 * "level state looks the same everywhere" stays true under future
 * palette tweaks.
 *
 * Not a theme-driven token: these are brand-specific swatches tied to
 * the visual identity of the progression system, the same way
 * [PokerPalette]'s chip-gold is tied to a physical chip. They render
 * identically under any future light/dark theme.
 */
val LevelProgressGradient: Brush = Brush.linearGradient(
    listOf(PokerPalette.ProgressionCyan, PokerPalette.ProgressionGreen),
)

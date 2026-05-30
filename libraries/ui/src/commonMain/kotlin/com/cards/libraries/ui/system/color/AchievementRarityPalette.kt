package com.dangerfield.cards.libraries.ui.system.color

import androidx.compose.ui.graphics.Color

/**
 * Accent hues that signal achievement rarity tiers — medallions, recent-
 * unlock tiles, and anywhere else the UI tints something by its rarity.
 *
 * These are *not* themed tokens. Rarity carries a fixed identity (a gold
 * Legendary should look the same in any future light/dark theme), so the
 * hex literals live here instead of `AppTheme.colors`. Epic reuses
 * [PokerPalette.ChipGold] so the chip-gold and Epic-rarity glow share a
 * single source of truth.
 *
 * Centralised per the AGENTS.md raw-`Color(0xFF…)` rule: raw literals
 * live under `:libraries:ui/system/color/`, not scattered across
 * component files. The rarity-to-color mapping itself lives at
 * [com.dangerfield.cards.libraries.ui.components.achievement.toAccentColor]
 * so the `AchievementRarity` ↔ `Color` adapter sits next to the
 * components that consume it.
 */
object AchievementRarityPalette {
    val Common: Color = Color(0xFFB08D57)
    val Rare: Color = Color(0xFFB0B0B8)
    val Epic: Color = PokerPalette.ChipGold
    val Legendary: Color = Color(0xFFE07AB1)
}

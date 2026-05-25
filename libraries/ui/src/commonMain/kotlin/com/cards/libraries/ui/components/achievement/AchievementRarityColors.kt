package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette

/**
 * Single source of truth for the rarity → accent color mapping used by
 * achievement medallions, recent-unlock tiles, and anywhere else the UI
 * tints something by its rarity. Lifted out of feature code so two
 * surfaces can't drift on the same idea.
 *
 * Not a `Color` themed token — these are deliberately fixed hues that
 * signal rarity tier regardless of the surrounding theme (the rarity
 * means the same thing in dark or light).
 */
fun AchievementRarity.toAccentColor(): Color = when (this) {
    AchievementRarity.COMMON -> Color(0xFFB08D57)
    AchievementRarity.RARE -> Color(0xFFB0B0B8)
    AchievementRarity.EPIC -> PokerPalette.ChipGold
    AchievementRarity.LEGENDARY -> Color(0xFFE07AB1)
}

package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.ui.system.color.AchievementRarityPalette

/**
 * Single source of truth for the rarity → accent color mapping used by
 * achievement medallions, recent-unlock tiles, and anywhere else the UI
 * tints something by its rarity. Lifted out of feature code so two
 * surfaces can't drift on the same idea.
 *
 * Not a `Color` themed token — these are deliberately fixed hues that
 * signal rarity tier regardless of the surrounding theme (the rarity
 * means the same thing in dark or light). Concrete hex values live in
 * [AchievementRarityPalette].
 */
fun AchievementRarity.toAccentColor(): Color = when (this) {
    AchievementRarity.COMMON -> AchievementRarityPalette.Common
    AchievementRarity.RARE -> AchievementRarityPalette.Rare
    AchievementRarity.EPIC -> AchievementRarityPalette.Epic
    AchievementRarity.LEGENDARY -> AchievementRarityPalette.Legendary
}

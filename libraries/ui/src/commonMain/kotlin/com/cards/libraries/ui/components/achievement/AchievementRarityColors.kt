package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.ui.system.color.AchievementRarityPalette
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette

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

/**
 * Background tint for an achievement-unlock celebration card. Deliberately
 * a single-family (ChipGold) ramp scaling by rarity — rarer unlocks get a
 * more saturated wash so a Legendary celebration reads heavier than a
 * Common one without changing hue. Distinct from [toAccentColor] (which
 * picks the rarity's *identity* color); this is the "how loud should the
 * celebration moment feel" knob.
 */
fun AchievementRarity.toCelebrationTint(): Color = when (this) {
    AchievementRarity.COMMON -> PokerPalette.ChipGold.copy(alpha = 0.12f)
    AchievementRarity.RARE -> PokerPalette.ChipGold.copy(alpha = 0.18f)
    AchievementRarity.EPIC -> PokerPalette.ChipGold.copy(alpha = 0.24f)
    AchievementRarity.LEGENDARY -> PokerPalette.ChipGold.copy(alpha = 0.30f)
}

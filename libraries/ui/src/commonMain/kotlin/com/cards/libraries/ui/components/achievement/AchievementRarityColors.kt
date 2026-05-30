package com.dangerfield.cards.libraries.ui.components.achievement

import androidx.compose.ui.graphics.Color
import com.dangerfield.cards.libraries.cards.AchievementRarity
import com.dangerfield.cards.libraries.ui.system.color.ColorResource

/**
 * Single source of truth for the rarity → accent color mapping used by
 * achievement medallions, recent-unlock tiles, and anywhere else the UI
 * tints something by its rarity. Lifted out of feature code so two
 * surfaces can't drift on the same idea.
 *
 * The tier colors themselves are the categorical `AppTheme.colors.rarity.*`
 * tokens (Epic intentionally reuses the chip gold from `colors.poker`). These
 * are deliberately fixed hues that signal rarity tier regardless of theme.
 * This adapter is a plain function (not `@Composable`) so it can be called
 * from `remember {}` blocks; it reads the tokens' backing [ColorResource]s
 * directly rather than going through the composable `AppTheme.colors`.
 */
fun AchievementRarity.toAccentColor(): Color = when (this) {
    AchievementRarity.COMMON -> ColorResource.RarityCommon.color
    AchievementRarity.RARE -> ColorResource.RarityRare.color
    AchievementRarity.EPIC -> ColorResource.PokerChipGold.color
    AchievementRarity.LEGENDARY -> ColorResource.RarityLegendary.color
}

/**
 * Background tint for an achievement-unlock celebration card. Deliberately
 * a single-family (chip gold) ramp scaling by rarity — rarer unlocks get a
 * more saturated wash so a Legendary celebration reads heavier than a
 * Common one without changing hue. Distinct from [toAccentColor] (which
 * picks the rarity's *identity* color); this is the "how loud should the
 * celebration moment feel" knob.
 */
fun AchievementRarity.toCelebrationTint(): Color = when (this) {
    AchievementRarity.COMMON -> ColorResource.PokerChipGold.color.copy(alpha = 0.12f)
    AchievementRarity.RARE -> ColorResource.PokerChipGold.color.copy(alpha = 0.18f)
    AchievementRarity.EPIC -> ColorResource.PokerChipGold.color.copy(alpha = 0.24f)
    AchievementRarity.LEGENDARY -> ColorResource.PokerChipGold.color.copy(alpha = 0.30f)
}

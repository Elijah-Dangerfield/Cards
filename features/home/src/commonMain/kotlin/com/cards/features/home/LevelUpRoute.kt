package com.dangerfield.cards.features.home

import com.dangerfield.cards.libraries.navigation.AnimationType
import com.dangerfield.cards.libraries.navigation.Route
import kotlinx.serialization.Serializable

/**
 * Full-screen level-up celebration. Routed (not a Home overlay) so it's a real
 * destination with no bottom bar, dismissed only by its own Continue button — a
 * stray tap can't dismiss it, and the app chrome doesn't bleed through. The Home
 * entry point navigates here off the derived level-up gate (`HomeState
 * .levelUpCelebration`) when the user's level crosses the celebrated watermark.
 *
 * Rewards are passed as the aggregated prizes the celebration reveals — a single
 * summed chip prize ([chipsRewarded]; 0 = none), a single XP-boost row
 * ([xpBoostRewarded]), and a single cosmetic row ([cosmeticProductId]; null =
 * none). The screen reconstructs the display rows; the grant itself already
 * happened in `LevelUpRewardGranter` (this is the reveal).
 */
@Serializable
data class LevelUpRoute(
    val level: Int,
    val chipsRewarded: Long,
    val xpBoostRewarded: Boolean,
    val cosmeticProductId: String? = null,
) : Route(
    enter = AnimationType.FadeIn,
    exit = AnimationType.FadeOut,
    popExit = AnimationType.FadeOut,
)

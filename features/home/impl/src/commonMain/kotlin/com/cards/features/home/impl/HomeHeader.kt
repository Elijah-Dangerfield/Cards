package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BalancePillSlot
import com.dangerfield.cards.libraries.ui.components.LevelPill
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Slim "you are here" header for Home. Two affordances, opposite ends:
 *
 *  - Left: [LevelPill] — current level + a gradient ring encoding XP
 *    progress to the next level. Taps into Stats.
 *  - Right: chip-balance pill via [BalancePillSlot] so the wallet
 *    affordance reads identically on Home and Shop. Taps into Shop.
 *
 * No avatar on Home — profile is the avatar's home (bottom-nav tab).
 * Duplicating it here cluttered the header without adding a real
 * tap-back path users were missing.
 */
@Composable
internal fun HomeHeader(
    levelProgress: LevelProgress,
    chips: Long?,
    onTapLevel: () -> Unit,
    onTapChips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BalancePillSlot(
        chips = chips,
        onChipsClick = onTapChips,
        modifier = modifier,
        leading = {
            // BalancePillSlot's row uses Alignment.Top; anchor the level
            // pill to vertical center so its ring sits at the same y as
            // the chip badge's text.
            Box(
                modifier = Modifier,
                contentAlignment = Alignment.CenterStart,
            ) {
                LevelPill(progress = levelProgress, onClick = onTapLevel)
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Previews — fully hydrated mid-level + a cold-boot null-chips state so the
// chip pill's em-dash placeholder is exercised next to the live ring.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun HomeHeaderPreview_MidLevel() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        HomeHeader(
            levelProgress = levelProgressFor(totalXp = 1_140),
            chips = 12_300,
            onTapLevel = {},
            onTapChips = {},
        )
    }
}

@Preview
@Composable
private fun HomeHeaderPreview_HydratingFromCold() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        HomeHeader(
            levelProgress = LevelProgress(level = 1, totalXp = 0, xpAtLevelStart = 0, xpForNextLevel = 100),
            chips = null,
            onTapLevel = {},
            onTapChips = {},
        )
    }
}

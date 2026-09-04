package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_header_xp
import cards.libraries.resources.generated.resources.home_level_chip
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.cutout
import com.dangerfield.cards.libraries.ui.components.ChipBadge
import com.dangerfield.cards.libraries.ui.components.RingedAvatar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Slim "you are here" header for Home. Two affordances, opposite ends:
 *
 *  - Left: the user's [RingedAvatar] (avatar wrapped in the level pill's XP
 *    progress ring) + display name + a "Level N" line. Taps into Stats.
 *  - Right: chip-balance pill via [ChipBadge] so the wallet affordance reads
 *    identically on Home and Shop. Taps into Shop.
 *
 * The avatar's ring encodes XP progress to the next level; the explicit level
 * number lives in the supporting line rather than a separate badge.
 */
@Composable
internal fun HomeHeader(
    levelProgress: LevelProgress,
    displayName: String,
    avatarEmoji: String?,
    avatarBackgroundColorHex: String?,
    chips: Long?,
    onTapLevel: () -> Unit,
    onTapChips: () -> Unit,
    modifier: Modifier = Modifier,
    /** Replays a missed chip change on return — rolls the odometer from
     *  [chipsRevealFrom] to [chips] when [chipsRevealKey] flips. */
    chipsRevealFrom: Long? = null,
    chipsRevealKey: Int = 0,
    /** True while a wallet reconcile is in flight — renders the balance as
     *  "updating" rather than a confidently-wrong pre-settlement value. */
    chipsReconciling: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The chips pill keeps its natural width (no weight); the name block
        // takes the remaining space and the name ellipsizes, so a long name
        // can never smush the wallet.
        Row(
            // No .clip here: it would crop the "LV N" chip that straddles the
            // avatar's bottom edge, flattening its rounded underside.
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onTapLevel)
                .padding(end = Dimension.D300),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            Box(contentAlignment = Alignment.BottomCenter) {
                RingedAvatar(
                    name = displayName,
                    fraction = levelProgress.fraction,
                    emoji = avatarEmoji,
                    backgroundColorHex = avatarBackgroundColorHex,
                    avatarSize = 56.dp,
                )
                // "LV N" chip straddling the avatar's bottom edge, cut out of the
                // page background. Teal text keeps the XP colour language; the XP
                // ring already encodes progress, so the number lives here.
                Box(
                    modifier = Modifier
                        .offset(y = 4.dp)
                        .cutout(
                            ringColor = AppTheme.colors.background.color,
                            fillColor = AppTheme.colors.surfaceRaised.color,
                            shape = Radii.Round.shape,
                            ringWidth = 2.dp,
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.home_level_chip, levelProgress.level),
                        typography = AppTheme.typography.Label.L400,
                        color = AppTheme.colors.accentSecondary,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = displayName,
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        Res.string.home_header_xp,
                        levelProgress.xpIntoLevel.toString(),
                        levelProgress.xpForNextLevel.toString(),
                    ),
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(Dimension.D300))
        // Pinned to the top of the header row (not vertically centered against the
        // taller avatar) and given the same shadow as the Shop's floating wallet,
        // so the chip badge lands in the exact same screen spot on Home and Shop —
        // it reads as one shared element when you switch tabs.
        ChipBadge(
            amount = chips,
            onClick = onTapChips,
            revealFrom = chipsRevealFrom,
            revealKey = chipsRevealKey,
            isReconciling = chipsReconciling,
            modifier = Modifier
                .align(Alignment.Top)
                .shadow(elevation = 6.dp, shape = Radii.Round.shape),
        )
    }
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
            displayName = "QuietAce72",
            avatarEmoji = "🦊",
            avatarBackgroundColorHex = "#E48A58",
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
            displayName = "You",
            avatarEmoji = null,
            avatarBackgroundColorHex = null,
            chips = null,
            onTapLevel = {},
            onTapChips = {},
        )
    }
}

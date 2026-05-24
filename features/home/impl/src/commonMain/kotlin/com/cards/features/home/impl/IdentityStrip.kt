package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AnimatedNumberText
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

/**
 * Top-of-Home identity strip per product-spec §2.4: avatar + display
 * name + a compact "level / rank" line on one side, with the user's
 * chip stack pinned to the trailing edge.
 *
 * Chips lean type-driven (large gold count, small "chips" label
 * underneath) so the wallet reads as the brand moment up top rather
 * than a tiny pill lost in chrome. Tapping the chip block routes to
 * the shop, mirroring the rest of the app's "tap your money to get
 * more" pattern.
 *
 * Level is a soft derive off XP (every 250 XP) — display-only, no
 * gameplay impact yet. Lives here, not in the VM, because the rule
 * is screen-shaped, not domain-shaped; cleaner to revisit the curve
 * locally when the progression team lands a real one.
 */
@Composable
internal fun IdentityStrip(
    displayName: String?,
    avatarEmoji: String?,
    avatarBackgroundColorHex: String?,
    xp: Long,
    rank: Int,
    chips: Long?,
    onTapProfile: () -> Unit,
    onTapChips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Dimension.D800))
                .clickable(onClick = onTapProfile)
                .padding(end = Dimension.D300),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AvatarCircle(
                name = displayName.orEmpty(),
                emoji = avatarEmoji,
                backgroundColorHex = avatarBackgroundColorHex,
                size = 56.dp,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Dimension.D500),
            ) {
                Text(
                    text = displayName ?: "Guest",
                    typography = AppTheme.typography.Heading.H700,
                    color = AppTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = levelAndRankLine(xp = xp, rank = rank),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ChipStackPill(chips = chips, onClick = onTapChips)
    }
}

@Composable
private fun ChipStackPill(chips: Long?, onClick: () -> Unit) {
    val goldText = ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold")
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimension.D700))
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D300),
    ) {
        if (chips == null) {
            Text(
                text = "—",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.textSecondary,
            )
        } else {
            AnimatedNumberText(
                value = chips,
                typography = AppTheme.typography.Heading.H700,
                color = goldText,
            )
        }
        Text(
            text = "CHIPS",
            typography = AppTheme.typography.Label.L300,
            color = AppTheme.colors.textSecondary,
        )
    }
}

/** "Lvl 4 · Unranked" / "Lvl 12 · Rank 1320". */
private fun levelAndRankLine(xp: Long, rank: Int): String {
    val level = (xp / 250L).coerceAtLeast(0).toInt() + 1
    val rankBit = if (rank <= 0) "Unranked" else "Rank $rank"
    return "Lvl $level · $rankBit"
}

package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.AnimatedNumberText
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension

/**
 * Slim "you are here" header for Home — a tappable avatar that
 * jumps to the profile screen, plus the chip pill anchored opposite.
 *
 * Intentionally minimal: name + level + rank are the profile
 * screen's job, not Home's. Duplicating them up top would clutter
 * the surface and bury the activity ticker / CTAs that Home is
 * actually about. The avatar acts as the affordance back into
 * profile, the chip pill as the affordance into the shop — both
 * one-tap, one-glance.
 */
@Composable
internal fun HomeHeader(
    avatarName: String?,
    avatarEmoji: String?,
    avatarBackgroundColorHex: String?,
    chips: Long?,
    onTapAvatar: () -> Unit,
    onTapChips: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        AvatarCircle(
            name = avatarName.orEmpty(),
            emoji = avatarEmoji,
            backgroundColorHex = avatarBackgroundColorHex,
            size = 40.dp,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onTapAvatar),
        )
        ChipPill(chips = chips, onClick = onTapChips)
    }
}

@Composable
private fun ChipPill(chips: Long?, onClick: () -> Unit) {
    val goldText = ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(AppTheme.colors.surfaceSecondary.color)
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D500, vertical = Dimension.D300),
    ) {
        Text(
            text = "◆",
            typography = AppTheme.typography.Body.B500,
            color = goldText,
        )
        if (chips == null) {
            Text(
                text = "—",
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.textSecondary,
            )
        } else {
            AnimatedNumberText(
                value = chips,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.text,
            )
        }
    }
}

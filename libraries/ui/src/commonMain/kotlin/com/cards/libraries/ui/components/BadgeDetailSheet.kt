package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.profile_item_sheet_earned
import cards.libraries.resources.generated.resources.ui_badge_sheet_done
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.achievement.earnedAgo
import com.dangerfield.cards.libraries.ui.components.achievement.label
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonSize
import com.dangerfield.cards.libraries.ui.components.button.ButtonStyle
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import kotlin.time.Clock

/**
 * "Read about this badge" sheet — opened by tapping a badge/title chip on any
 * Player Card (your own or another player's). Read-only: emoji hero, name,
 * description, and "Earned … ago" when the acquired-at time is known. Equipping
 * lives on the profile's item detail sheet, not here, so this is safe to show
 * for a badge that isn't yours.
 */
@Composable
fun BadgeDetailSheet(
    badge: PlayerBadge,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        onDismissRequest = onDismiss,
        showCloseButton = true,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimension.D500, vertical = Dimension.D400),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(Radii.R700.shape)
                    .background(AppTheme.colors.surfaceRaised.color),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = badge.emoji, typography = AppTheme.typography.Display.D900)
            }
            VerticalSpacerD500()
            Text(
                text = badge.name,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            badge.description?.takeIf { it.isNotBlank() }?.let { desc ->
                VerticalSpacerD200()
                Text(
                    text = desc,
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            badge.earnedAtEpochMs?.let { earnedAt ->
                // Pin a stable "now" in previews so the relative label doesn't
                // depend on the wall clock.
                val now = if (LocalInspectionMode.current) {
                    earnedAt + 3L * 24 * 60 * 60 * 1000
                } else {
                    Clock.System.now().toEpochMilliseconds()
                }
                VerticalSpacerD300()
                Text(
                    text = stringResource(
                        Res.string.profile_item_sheet_earned,
                        earnedAgo(earnedAt, now).label(),
                    ),
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.contentSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            VerticalSpacerD800()
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                size = ButtonSize.Medium,
                style = ButtonStyle.Filled,
            ) {
                Text(stringResource(Res.string.ui_badge_sheet_done))
            }
        }
    }
}

@Preview
@Composable
private fun BadgeDetailSheetPreview() {
    PreviewContent {
        BadgeDetailSheet(
            badge = PlayerBadge(
                productId = "title_pot_magnet",
                emoji = "🧲",
                name = "Pot Magnet",
                description = "Unlocked by sitting at a 5,000-chip pot. Shows under your name at the table.",
                earnedAtEpochMs = 1_700_000_000_000,
            ),
            onDismiss = {},
        )
    }
}

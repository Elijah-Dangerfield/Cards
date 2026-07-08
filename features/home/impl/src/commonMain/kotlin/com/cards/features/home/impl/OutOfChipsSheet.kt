package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_out_of_chips_body
import cards.libraries.resources.generated.resources.home_out_of_chips_buy_subtitle
import cards.libraries.resources.generated.resources.home_out_of_chips_buy_title
import cards.libraries.resources.generated.resources.home_out_of_chips_earn_subtitle
import cards.libraries.resources.generated.resources.home_out_of_chips_earn_title
import cards.libraries.resources.generated.resources.home_out_of_chips_title
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * "Short stacked" sheet — presented once per below-buy-in episode when the
 * balance can't cover a Casual seat (see `HomeNotification.OutOfChips`). Two
 * honest ways back: a chip pack from the shop, or the free-to-enter bot
 * tables whose level-ups and achievements grant real chips. Deliberately no
 * countdowns and no discounts — per voice-and-copy: no urgency, no begging.
 *
 * Presented as transient Home state like [PrivateChooseSheet]; both rows
 * dismiss and route.
 */
@Composable
internal fun OutOfChipsSheet(
    casualBuyIn: Long,
    onGetChips: () -> Unit,
    onPlayBots: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        title = stringResource(Res.string.home_out_of_chips_title),
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.background,
        dragHandle = topAccessoryEmoji(emoji = "🪙").asDragHandle(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(
                    Res.string.home_out_of_chips_body,
                    formatThousands(casualBuyIn),
                ),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
            Spacer(modifier = Modifier.height(Dimension.D600))

            // Buy — gold hero, same weighting Create gets on the private sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R900.shape)
                    .background(FeatureCardAccents.Gold.copy(alpha = 0.85f))
                    .clickable(onClick = onGetChips)
                    .padding(Dimension.D800),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                EmojiTile(emoji = "💰")
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.home_out_of_chips_buy_title),
                        typography = AppTheme.typography.Heading.H700,
                        color = AppTheme.colors.content,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.home_out_of_chips_buy_subtitle),
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.content.withAlpha(0.78f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimension.D500))

            // Earn — neutral surface card, the free path.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R850.shape)
                    .background(AppTheme.colors.surface.color)
                    .border(width = 1.dp, color = AppTheme.colors.border.color, shape = Radii.R850.shape)
                    .clickable(onClick = onPlayBots)
                    .padding(Dimension.D700),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                EmojiTile(emoji = "🤖")
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.home_out_of_chips_earn_title),
                        typography = AppTheme.typography.Heading.H700,
                        color = AppTheme.colors.content,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.home_out_of_chips_earn_subtitle),
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.contentSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiTile(emoji: String) {
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(Radii.R600.shape)
            .background(AppTheme.colors.content.color.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, typography = AppTheme.typography.Heading.H800)
    }
}

@Preview
@Composable
private fun OutOfChipsSheetPreview() {
    PreviewContent {
        OutOfChipsSheet(
            casualBuyIn = 1_000,
            onGetChips = {},
            onPlayBots = {},
            onDismiss = {},
        )
    }
}

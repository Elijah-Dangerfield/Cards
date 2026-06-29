package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.private_choose_create_subtitle
import cards.libraries.resources.generated.resources.private_choose_create_title
import cards.libraries.resources.generated.resources.private_choose_join_subtitle
import cards.libraries.resources.generated.resources.private_choose_join_title
import cards.libraries.resources.generated.resources.private_choose_title
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.asDragHandle
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.resources.stringResource

/**
 * Private-room entry sheet (SPEC §1) — a momentary two-way fork presented over
 * Home: **Create a room** (gold, host a table) or **Join with a code** (teal,
 * enter a host's code). It only routes and dismisses; both branches push their
 * own full screens. Recent-rooms list intentionally removed.
 *
 * Presented as transient Home state (`showPrivateSheet`), not a back-stack
 * destination — dismiss just clears the flag.
 */
@Composable
internal fun PrivateChooseSheet(
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    BottomSheet(
        title = stringResource(Res.string.private_choose_title),
        onDismissRequest = onDismiss,
        backgroundColor = AppTheme.colors.background,
        // Match the home row's lock glyph; flat bubble (no gradient panel).
        dragHandle = topAccessoryEmoji(emoji = "🔒").asDragHandle(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Create — flat gold hero (the gradient panel read as inconsistent
            // next to the now-flat Play cards).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R900.shape)
                    .background(FeatureCardAccents.Gold.copy(alpha = 0.85f))
                    .clickable(onClick = onCreate)
                    .padding(Dimension.D800),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                PlusTile()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.private_choose_create_title),
                        typography = AppTheme.typography.Heading.H700,
                        color = AppTheme.colors.content,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.private_choose_create_subtitle),
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.content.withAlpha(0.78f),
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimension.D500))

            // Join — neutral surface card with teal grid glyph + chevron.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Radii.R850.shape)
                    .background(AppTheme.colors.surface.color)
                    .border(width = 1.dp, color = AppTheme.colors.border.color, shape = Radii.R850.shape)
                    .clickable(onClick = onJoin)
                    .padding(Dimension.D700),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
            ) {
                GridTile()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.private_choose_join_title),
                        typography = AppTheme.typography.Heading.H700,
                        color = AppTheme.colors.content,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(Res.string.private_choose_join_subtitle),
                        typography = AppTheme.typography.Body.B400,
                        color = AppTheme.colors.contentSecondary,
                    )
                }
                val chevronColor = AppTheme.colors.content.color
                Canvas(modifier = Modifier.size(16.dp)) { drawSheetChevron(chevronColor) }
            }
        }
    }
}

@Composable
private fun PlusTile() {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(Radii.R600.shape)
            .background(AppTheme.colors.content.color.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "+", typography = AppTheme.typography.Heading.H900, color = AppTheme.colors.content)
    }
}

@Composable
private fun GridTile() {
    val teal = AppTheme.colors.accentSecondary.color
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(Radii.R600.shape)
            .background(AppTheme.colors.surfaceRaised.color),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val cell = size.width * 0.38f
            val gap = size.width * 0.24f
            // Two filled + two outlined squares — a "code grid" mark.
            drawRect(teal, topLeft = Offset(0f, 0f), size = Size(cell, cell))
            drawRect(teal, topLeft = Offset(cell + gap, cell + gap), size = Size(cell, cell))
            drawRect(teal, topLeft = Offset(cell + gap, 0f), size = Size(cell, cell), style = Stroke(width = size.width * 0.07f))
            drawRect(teal, topLeft = Offset(0f, cell + gap), size = Size(cell, cell), style = Stroke(width = size.width * 0.07f))
        }
    }
}

private fun DrawScope.drawSheetChevron(color: Color) {
    val w = size.width
    val h = size.height
    val s = w * 0.16f
    drawLine(color, Offset(w * 0.35f, h * 0.2f), Offset(w * 0.7f, h * 0.5f), strokeWidth = s)
    drawLine(color, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.35f, h * 0.8f), strokeWidth = s)
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun PrivateChooseSheetPreview() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        PrivateChooseSheet(onCreate = {}, onJoin = {}, onDismiss = {})
    }
}

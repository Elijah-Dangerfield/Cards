package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.formatCompactChips
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Small gold pill showing a chip amount — used for "contributed this street",
 * pot subtotals, and other in-table chip callouts.
 *
 * Numbers are compact-formatted (`1.2k`, `1.2M`) and clipped to a single
 * non-wrapping line so a deep all-in stack can't blow the pill width out.
 */
@Composable
fun ChipPill(amount: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(PokerPalette.ChipGold)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = formatCompactChips(amount),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.background,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
private fun ChipPillPreview_Small() {
    PreviewContent { ChipPill(amount = 240) }
}

@Preview
@Composable
private fun ChipPillPreview_Large() {
    PreviewContent { ChipPill(amount = 1_234_567) }
}

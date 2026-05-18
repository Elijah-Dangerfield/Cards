package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * A horizontal row of equally-weighted, mutually-exclusive option pills.
 *
 * Generic over the option type so it works for seat counts, difficulty
 * presets, bet-size buckets, or anything else with a small enumerable set.
 * The selected option is accent-tinted; the others stay on a neutral
 * surface so the row reads as "pick one."
 *
 * Use this anywhere you want a Duolingo-style segmented choice — favor it
 * over a row of buttons or a real `RadioGroup`, which both feel form-y
 * against the rest of the app.
 */
@Composable
fun <T> OptionPillRow(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    pillHeight: androidx.compose.ui.unit.Dp = 56.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            OptionPill(
                label = label(option),
                selected = option == selected,
                modifier = Modifier.weight(1f),
                height = pillHeight,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun OptionPill(
    label: String,
    selected: Boolean,
    height: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) AppTheme.colors.accentPrimary.color
    else AppTheme.colors.surfaceSecondary.color
    val fg = if (selected) AppTheme.colors.onAccentPrimary else AppTheme.colors.onSurfacePrimary
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(percent = 50))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B600,
            color = fg,
        )
    }
}

@Preview
@Composable
private fun OptionPillRowPreview_SeatCount() {
    PreviewContent {
        OptionPillRow(
            options = listOf(2, 4, 6),
            selected = 4,
            onSelect = {},
            label = { "$it seats" },
        )
    }
}

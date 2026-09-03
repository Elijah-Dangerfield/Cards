package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.color.ProvideContentColor

/**
 * Dual-thumb range slider — the buy-in RANGE control on the public "Find a
 * table" screen ("I'm happy to buy in for X – Y"). Thin DS wrapper over the
 * material3 [androidx.compose.material3.RangeSlider] mirroring the single-thumb
 * `Slider` so the track/thumb derive from `LocalContentColor`; wrap the call
 * site in `ProvideContentColor(AppTheme.colors.accentPrimary)` for the gold
 * track the mock uses.
 */
@Composable
fun RangeSlider(
    value: ClosedFloatingPointRange<Float>,
    onValueChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val content = LocalContentColor.current
    androidx.compose.material3.RangeSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderColors(
            thumbColor = content,
            activeTrackColor = content,
            activeTickColor = content,
            inactiveTrackColor = content.copy(alpha = 0.4f),
            inactiveTickColor = content.copy(alpha = 0.4f),
            disabledThumbColor = content.copy(alpha = 0.4f),
            disabledActiveTrackColor = content.copy(alpha = 0.4f),
            disabledActiveTickColor = content.copy(alpha = 0.4f),
            disabledInactiveTrackColor = content.copy(alpha = 0.4f),
            disabledInactiveTickColor = content.copy(alpha = 0.4f),
        ),
    )
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun RangeSliderPreview() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        ProvideContentColor(AppTheme.colors.accentPrimary) {
            var range by remember { mutableStateOf(0.2f..0.6f) }
            RangeSlider(value = range, onValueChange = { range = it })
        }
    }
}

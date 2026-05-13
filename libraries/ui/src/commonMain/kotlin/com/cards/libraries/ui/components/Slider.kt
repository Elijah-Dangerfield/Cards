package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@Composable
fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        colors = SliderColors(
            thumbColor = LocalContentColor.current,
            activeTrackColor = LocalContentColor.current,
            activeTickColor = LocalContentColor.current,
            inactiveTrackColor = LocalContentColor.current.copy(alpha = 0.4f),
            inactiveTickColor = LocalContentColor.current.copy(alpha = 0.4f),
            disabledThumbColor = LocalContentColor.current.copy(alpha = 0.4f),
            disabledActiveTrackColor = LocalContentColor.current.copy(alpha = 0.4f),
            disabledActiveTickColor = LocalContentColor.current.copy(alpha = 0.4f),
            disabledInactiveTrackColor = LocalContentColor.current.copy(alpha = 0.4f),
            disabledInactiveTickColor = LocalContentColor.current.copy(alpha = 0.4f)
        ),
        interactionSource = interactionSource,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished
    )
}
package com.dangerfield.cards.libraries.ui.components.radio

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.PreviewContent

@Composable
fun RadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: com.dangerfield.cards.libraries.ui.components.radio.RadioButtonColors = com.dangerfield.cards.libraries.ui.components.radio.RadioButtonDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {

    androidx.compose.material3.RadioButton(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = colors.toMaterial(),
        interactionSource = interactionSource
    )
}

@Preview
@Composable
private fun PreviewButton() {
    PreviewContent {
        var selected by remember { mutableStateOf(false) }
        com.dangerfield.cards.libraries.ui.components.radio.RadioButton(
            selected = selected,
            onClick = { selected = !selected })
    }
}

@Preview
@Composable
private fun PreviewButtonUnselected() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.radio.RadioButton(selected = false, onClick = { })
    }
}

@Preview
@Composable
private fun PreviewButtonSelected() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.radio.RadioButton(selected = true, onClick = { })
    }
}

@Preview
@Composable
private fun PreviewButtonUnselectedDisabled() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.radio.RadioButton(
            selected = false,
            onClick = { },
            enabled = false
        )
    }
}

@Preview
@Composable
private fun PreviewButtonSelectedDisabled() {
    PreviewContent {
        com.dangerfield.cards.libraries.ui.components.radio.RadioButton(
            selected = true,
            onClick = { },
            enabled = false
        )
    }
}
object RadioButtonDefaults {
    @Composable
    fun colors(
        selectedColor: Color = AppTheme.colors.accentPrimary.color,    // was onBackground — THE change
        unselectedColor: Color = AppTheme.colors.borderStrong.color,   // was onBackground — too loud for a rest ring
        disabledSelectedColor: Color = AppTheme.colors.contentDisabled.color,
        disabledUnselectedColor: Color = AppTheme.colors.contentDisabled.color
    ): com.dangerfield.cards.libraries.ui.components.radio.RadioButtonColors =
        com.dangerfield.cards.libraries.ui.components.radio.RadioButtonColors(
            selectedColor,
            unselectedColor,
            disabledSelectedColor,
            disabledUnselectedColor
        )
}

@Immutable
data class RadioButtonColors (
    val selectedColor: Color,
    val unselectedColor: Color,
    val disabledSelectedColor: Color,
    val disabledUnselectedColor: Color
)

private fun com.dangerfield.cards.libraries.ui.components.radio.RadioButtonColors.toMaterial() = androidx.compose.material3.RadioButtonColors(
    selectedColor = selectedColor,
    unselectedColor = unselectedColor,
    disabledSelectedColor = disabledSelectedColor,
    disabledUnselectedColor = disabledUnselectedColor
)
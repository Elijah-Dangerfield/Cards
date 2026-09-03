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
import androidx.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.libraries.ui.catalog.RADIO_SUBTITLE
import com.dangerfield.cards.libraries.ui.catalog.RadioCatalogBody
import com.dangerfield.cards.libraries.ui.catalog.CatalogPage
import com.dangerfield.cards.system.AppTheme

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

@Preview(widthDp = 600, heightDp = 320)
@Composable
private fun RadioPreview() {
    CatalogPage(title = "Radio", subtitle = RADIO_SUBTITLE) { RadioCatalogBody() }
}

package com.dangerfield.cards.libraries.ui.components.checkbox

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.PreviewContent

@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = com.dangerfield.cards.libraries.ui.components.checkbox.CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    androidx.compose.material3.Checkbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = colors.toMaterial(),
        interactionSource = interactionSource

    )
}

@Composable
@Preview
private fun Unchecked() {
    PreviewContent {
        Checkbox(checked = false, onCheckedChange = {})
    }
}

@Composable
@Preview
private fun Checked() {
    PreviewContent {
        Checkbox(checked = true, onCheckedChange = {})
    }
}

@Composable
@Preview
private fun DisabledChecked() {
    PreviewContent {
        Checkbox(
            checked = true,
            enabled = false,
            onCheckedChange = {}
        )
    }
}

@Composable
@Preview
private fun DisabledUnChecked() {
    PreviewContent {
        Checkbox(
            checked = false,
            enabled = false,
            onCheckedChange = {}
        )
    }
}


object CheckboxDefaults {
    @Composable
    fun colors() = CheckboxColors(
        checkedCheckmarkColor = AppTheme.colors.onAccentPrimary.color,
        uncheckedCheckmarkColor = Color.Transparent,
        checkedBoxColor = AppTheme.colors.accentPrimary.color,
        uncheckedBoxColor = AppTheme.colors.surfaceSecondary.color,
        disabledCheckedBoxColor = AppTheme.colors.surfaceDisabled.color,
        disabledUncheckedBoxColor = AppTheme.colors.surfaceDisabled.color,
        disabledIndeterminateBoxColor = AppTheme.colors.surfaceDisabled.color,
        checkedBorderColor = AppTheme.colors.accentPrimary.color,
        uncheckedBorderColor = AppTheme.colors.border.color,
        disabledBorderColor = AppTheme.colors.borderDisabled.color,
        disabledUncheckedBorderColor = AppTheme.colors.borderDisabled.color,
        disabledIndeterminateBorderColor = AppTheme.colors.borderDisabled.color
    )
}

@Immutable
data class CheckboxColors (
    val checkedCheckmarkColor: Color,
    val uncheckedCheckmarkColor: Color,
    val checkedBoxColor: Color,
    val uncheckedBoxColor: Color,
    val disabledCheckedBoxColor: Color,
    val disabledUncheckedBoxColor: Color,
    val disabledIndeterminateBoxColor: Color,
    val checkedBorderColor: Color,
    val uncheckedBorderColor: Color,
    val disabledBorderColor: Color,
    val disabledUncheckedBorderColor: Color,
    val disabledIndeterminateBorderColor: Color
)

internal fun CheckboxColors.toMaterial() = androidx.compose.material3.CheckboxColors(
    checkedCheckmarkColor = checkedCheckmarkColor,
    uncheckedCheckmarkColor = uncheckedCheckmarkColor,
    checkedBoxColor = checkedBoxColor,
    uncheckedBoxColor = uncheckedBoxColor,
    disabledCheckedBoxColor = disabledCheckedBoxColor,
    disabledUncheckedBoxColor = disabledUncheckedBoxColor,
    disabledIndeterminateBoxColor = disabledIndeterminateBoxColor,
    checkedBorderColor = checkedBorderColor,
    uncheckedBorderColor = uncheckedBorderColor,
    disabledBorderColor = disabledBorderColor,
    disabledUncheckedBorderColor = disabledUncheckedBorderColor,
    disabledIndeterminateBorderColor = disabledIndeterminateBorderColor
)
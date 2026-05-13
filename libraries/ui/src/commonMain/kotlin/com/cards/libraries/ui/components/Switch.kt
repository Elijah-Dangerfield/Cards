package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.libraries.ui.PreviewContent
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = SwitchDefaults.colors(
            uncheckedThumbColor = AppTheme.colors.onSurfacePrimary.color,
            uncheckedTrackColor = AppTheme.colors.surfacePrimary.color,
            uncheckedBorderColor = AppTheme.colors.onSurfacePrimary.color,
            checkedThumbColor = AppTheme.colors.onAccentPrimary.color,
            checkedTrackColor = AppTheme.colors.accentPrimary.color,
            checkedBorderColor = AppTheme.colors.accentPrimary.color,
            disabledCheckedBorderColor = AppTheme.colors.onSurfaceDisabled.color,
            disabledUncheckedBorderColor = AppTheme.colors.onSurfaceDisabled.color,
            disabledCheckedThumbColor = AppTheme.colors.onSurfaceDisabled.color,
            disabledUncheckedThumbColor = AppTheme.colors.onSurfaceDisabled.color,
            disabledCheckedTrackColor = AppTheme.colors.surfaceDisabled.color,
            disabledUncheckedTrackColor = AppTheme.colors.surfaceDisabled.color
        )
    )
}

@Composable
@Preview
private fun Unchecked() {
    PreviewContent {
        Switch(checked = false, onCheckedChange = {})
    }
}

@Composable
@Preview
private fun Checked() {
    PreviewContent {
        Switch(checked = true, onCheckedChange = {})
    }
}

@Composable
@Preview
private fun CheckedDisabled() {
    PreviewContent {
        Switch(
            checked = true,
            onCheckedChange = {},
            enabled = false
        )
    }
}

@Composable
@Preview
private fun UncheckedDisabled() {
    PreviewContent {
        Switch(
            checked = false,
            onCheckedChange = {},
            enabled = false
        )
    }
}


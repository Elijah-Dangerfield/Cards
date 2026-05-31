package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import com.dangerfield.cards.libraries.ui.catalog.SWITCH_SUBTITLE
import com.dangerfield.cards.libraries.ui.catalog.SwitchCatalogBody
import com.dangerfield.cards.libraries.ui.catalog.CatalogPage

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
            uncheckedThumbColor = AppTheme.colors.content.color,
            uncheckedTrackColor = AppTheme.colors.surfaceHigh.color,   // was surfacePrimary — too dark on a card
            uncheckedBorderColor = AppTheme.colors.surfaceHigh.color,  // drop the bright white ring
            checkedThumbColor = AppTheme.colors.onAccentPrimary.color,
            checkedTrackColor = AppTheme.colors.accentPrimary.color,
            checkedBorderColor = AppTheme.colors.accentPrimary.color,
            disabledCheckedBorderColor = AppTheme.colors.contentDisabled.color,
            disabledUncheckedBorderColor = AppTheme.colors.contentDisabled.color,
            disabledCheckedThumbColor = AppTheme.colors.contentDisabled.color,
            disabledUncheckedThumbColor = AppTheme.colors.contentDisabled.color,
            disabledCheckedTrackColor = AppTheme.colors.surfaceDisabled.color,
            disabledUncheckedTrackColor = AppTheme.colors.surfaceDisabled.color
        )
    )
}

@Preview(widthDp = 520, heightDp = 320)
@Composable
private fun SwitchPreview() {
    CatalogPage(title = "Switch", subtitle = SWITCH_SUBTITLE) { SwitchCatalogBody() }
}

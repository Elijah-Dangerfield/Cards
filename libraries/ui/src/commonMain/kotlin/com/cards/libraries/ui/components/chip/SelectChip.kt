package com.dangerfield.cards.libraries.ui.components.chip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD1000
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Simple toggleable chip that reflects a selected state.
 */
@Composable
fun SelectChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                color = if (selected) {
                    AppTheme.colors.surfaceRaised.color
                } else {
                    AppTheme.colors.surface.color
                },
                shape = Radii.Round.shape
            )
            .border(
                width = 2.dp,
                color = if (selected) {
                    AppTheme.colors.accentPrimary.color
                } else {
                    AppTheme.colors.border.color
                },
                shape = Radii.Round.shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D600, vertical = Dimension.D400),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, typography = AppTheme.typography.Label.L600)
    }
}


@Composable
@Preview
private fun PreviewSelectChip() {
    PreviewContent {
        Column {
            SelectChip(label = "Hello", selected = true, onClick = {})

            VerticalSpacerD1000()

            SelectChip(label = "Hello", selected = false, onClick = {})
        }
    }
}
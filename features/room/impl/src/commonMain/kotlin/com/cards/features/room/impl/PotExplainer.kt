package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipCoin
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD100
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * One-tap explainer for "what is the pot?" — opened by tapping the gold pot
 * pill on the board. Kept short on purpose; the cheat sheet covers the rest
 * of the rules.
 */
@Composable
internal fun PotExplainer(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ChipCoin (DS) — see StackExplainer for the rationale.
            ChipCoin(size = 56.dp, textTypography = AppTheme.typography.Heading.H800)
            Text(
                text = "The pot",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Text(
                text = "Every bet and call this hand stacks into the pot. The player with the best hand at showdown wins it all.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            VerticalSpacerD100()
        }
    }
}

@Preview
@Composable
private fun PotExplainerPreview() {
    PreviewContent {
        PotExplainer(onDismiss = {})
    }
}

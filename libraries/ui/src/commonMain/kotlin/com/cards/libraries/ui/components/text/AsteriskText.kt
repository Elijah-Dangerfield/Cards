package com.dangerfield.cards.libraries.ui.components.text

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.HorizontalSpacerD200
import com.dangerfield.cards.libraries.ui.system.color.ColorResource

@Composable
fun AsteriskText(text: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        text()
        HorizontalSpacerD200()
        Text(
            text = "*",
            typography = AppTheme.typography.Display.D800,
            color = ColorResource.Red500
        )
    }
}
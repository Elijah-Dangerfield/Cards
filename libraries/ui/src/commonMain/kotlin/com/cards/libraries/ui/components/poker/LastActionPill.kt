package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Subtle rounded pill showing a player's last action ("Fold", "Call", "All in",
 * etc.). Caller is responsible for short-labeling the action (the engine's
 * `PlayerAction` types are sealed in the gameplay library, which this lib
 * doesn't depend on).
 */
@Composable
fun LastActionPill(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier = modifier
            .clip(Radii.Round.shape)
            // Lifted off the table surface via a DS token rather than a
            // hand-tuned alpha; matches the chip badge / XP badge family so
            // every pill in the app has the same surface temperature.
            .background(AppTheme.colors.surfaceTertiary.color)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
    }
}

@Preview
@Composable
private fun LastActionPillPreview_Fold() {
    PreviewContent { LastActionPill(label = "Fold") }
}

@Preview
@Composable
private fun LastActionPillPreview_AllIn() {
    PreviewContent { LastActionPill(label = "All in") }
}

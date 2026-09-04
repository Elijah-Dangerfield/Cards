package com.dangerfield.cards.libraries.ui.components.poker

import com.dangerfield.cards.system.AppTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Static gold halo behind a winning player's avatar. Intentionally static
 * (no animation) — at hand end multiple seats can render this simultaneously
 * and an infinite transition per seat would chew CPU for a one-shot moment.
 */
@Composable
fun WinnerGlow(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(AppTheme.colors.poker.chipGold.color.copy(alpha = 0.7f)),
    )
}

@Preview
@Composable
private fun WinnerGlowPreview() {
    PreviewContent {
        WinnerGlow(modifier = Modifier.size(64.dp))
    }
}

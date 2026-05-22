package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AvatarCircle
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Tilted "BUSTED" overlay layered over a seat when the player's stack has
 * been wiped out at the end of a hand. Mimics the classic ink-stamp feel —
 * caller renders this on top of a (dimmed) avatar so the seat reads as
 * eliminated at a glance.
 *
 * Caller owns the dimming and the parent sizing; this just paints the
 * stamp. Use `Modifier.align(Alignment.Center)` (or similar) from the parent
 * to position it over the avatar.
 */
@Composable
fun BustedStamp(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .graphicsLayer { rotationZ = -14f }
            .clip(RoundedCornerShape(4.dp))
            .background(AppTheme.colors.danger.color.copy(alpha = 0.18f))
            .border(
                width = 1.5.dp,
                color = AppTheme.colors.danger.color,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "BUSTED",
            typography = AppTheme.typography.Label.L300,
            color = AppTheme.colors.danger,
            maxLines = 1,
            softWrap = false,
            allCaps = true,
        )
    }
}

@Preview
@Composable
private fun BustedStampPreview() {
    PreviewContent {
        BustedStamp()
    }
}

@Preview
@Composable
private fun BustedStampOnAvatarPreview() {
    PreviewContent {
        Box(modifier = Modifier.size(72.dp)) {
            AvatarCircle(
                name = "Jane",
                size = 64.dp,
                emoji = "🧐",
            )
            BustedStamp(modifier = Modifier.align(Alignment.Center))
        }
    }
}

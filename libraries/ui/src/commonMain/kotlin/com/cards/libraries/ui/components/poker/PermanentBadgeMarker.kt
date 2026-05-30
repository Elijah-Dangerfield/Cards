package com.dangerfield.cards.libraries.ui.components.poker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Tiny chip showing the player's permanent seat badge — the visual
 * counterpart to [BlindMarker], laid out on the opposite side of the
 * avatar so blind-role chips and seat-recognition badges never collide.
 * Today the badge is a single emoji on a chip-gold surface (founding
 * member, league-tier rewards, etc.); future variants can extend the
 * surface tone via a sibling overload before drifting into a one-off
 * background per callsite.
 */
@Composable
fun PermanentBadgeMarker(
    emoji: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(Dimension.D850)
                .clip(CircleShape)
                .background(AppTheme.colors.surfaceRaised.color)
                .border(
                    width = 1.dp,
                    color = PokerPalette.ChipGold,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emoji,
                typography = AppTheme.typography.Label.L500,
                color = AppTheme.colors.content,
            )
        }
    }
}

@Preview
@Composable
private fun PermanentBadgeMarkerPreview_FoundingMember() {
    PreviewContent {
        PermanentBadgeMarker(emoji = "🏛")
    }
}

@Preview
@Composable
private fun PermanentBadgeMarkerPreview_Trophy() {
    PreviewContent {
        PermanentBadgeMarker(emoji = "🏆")
    }
}

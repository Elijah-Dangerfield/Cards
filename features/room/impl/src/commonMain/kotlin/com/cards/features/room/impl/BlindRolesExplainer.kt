package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD50
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Quick-reference modal explaining what the D / SB / BB chips on each player
 * mean. Triggered by tapping any of those markers — the hit area is
 * intentionally generous so the small visible chip is forgiving to find.
 */
@Composable
internal fun BlindRolesExplainer(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "🎲"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Table positions",
                typography = AppTheme.typography.Heading.H800,
                color = AppTheme.colors.onSurfacePrimary,
            )
            RoleRow(
                label = "D",
                bg = PokerPalette.DealerWhite,
                title = "Dealer",
                description = "Action moves clockwise from the player left of the dealer. The dealer position rotates each hand.",
            )
            RoleRow(
                label = "SB",
                bg = PokerPalette.ChipGold,
                title = "Small blind",
                description = "Forced half-bet, posted by the player immediately left of the dealer before any cards are dealt.",
            )
            RoleRow(
                label = "BB",
                bg = PokerPalette.BlindRed,
                title = "Big blind",
                description = "Forced full bet, posted left of the small blind. The minimum opening bet for the hand.",
            )
        }
    }
}

@Composable
private fun RoleRow(label: String, bg: Color, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                typography = AppTheme.typography.Body.B500.Bold,
                color = AppTheme.colors.background,
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD50()
            Text(
                text = description,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
        }
    }
}

@Preview
@Composable
private fun BlindRolesExplainerPreview() {
    PreviewContent {
        BlindRolesExplainer(onDismiss = {})
    }
}

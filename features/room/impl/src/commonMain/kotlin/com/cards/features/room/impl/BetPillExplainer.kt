package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD200
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Small targeted explainer opened by tapping a player's gold bet pill —
 * tells the user how many chips THIS player has put in this street so far,
 * and what that bucket represents. Less overwhelming than launching the
 * full cheat sheet.
 */
@Composable
internal fun BetPillExplainer(
    seatName: String,
    amount: Long,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PokerPalette.ChipGold),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$",
                    typography = AppTheme.typography.Heading.H600,
                    color = AppTheme.colors.background,
                )
            }
            VerticalSpacerD200()
            Text(
                text = "$seatName put in $amount",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "That's how many chips they've added to the pot on this betting round. It resets when the next card hits the board.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
private fun BetPillExplainerPreview() {
    PreviewContent {
        BetPillExplainer(seatName = "Jane", amount = 60, onDismiss = {})
    }
}

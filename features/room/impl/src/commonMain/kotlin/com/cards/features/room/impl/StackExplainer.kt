package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD100
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Quick-reference modal opened by tapping the user's own chip stack number on
 * the bot table — explains where these chips come from and that they refill
 * on bust, so a new player isn't worried about "losing" them.
 */
@Composable
internal fun StackExplainer(stack: Long, onDismiss: () -> Unit) {
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
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(PokerPalette.ChipGold),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$",
                    typography = AppTheme.typography.Heading.H800,
                    color = AppTheme.colors.background,
                )
            }
            Text(
                text = "You have $stack chips",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Use them to bet, call, and raise during a hand. Chips you put in go to the pot — the player with the best hand at showdown wins it.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Go bust against bots and a fresh stack arrives next hand. Practice chips don't count for keeps.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            VerticalSpacerD100()
        }
    }
}

@Preview
@Composable
private fun StackExplainerPreview() {
    PreviewContent {
        StackExplainer(stack = 1_240, onDismiss = {})
    }
}

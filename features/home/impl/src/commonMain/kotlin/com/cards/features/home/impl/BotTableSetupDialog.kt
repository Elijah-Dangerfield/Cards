package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.OptionPillRow
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.DialogEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD800

/**
 * Pre-game configuration dialog for the bot table. Picks the seat count
 * (table size) before navigating into the game. The difficulty is locked
 * by the home-screen entry point that opened the dialog.
 *
 * Table size meaningfully changes hand pace: heads-up is fast and
 * aggressive, 6-max is positional and slower. This dialog teaches that
 * implicitly via the subtitle copy.
 */
@Composable
internal fun BotTableSetupDialog(
    difficultyLabel: String,
    onStart: (seatCount: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var seatCount by remember { mutableStateOf(4) }
    Dialog(
        onDismissRequest = onDismiss,
        emoji = DialogEmoji(emoji = "🤖"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text(
                text = "Table size",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
            )
            VerticalSpacerD300()
            Text(
                text = subtitleFor(seatCount, difficultyLabel),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
            )
            VerticalSpacerD800()
            OptionPillRow(
                options = listOf(2, 4, 6),
                selected = seatCount,
                onSelect = { seatCount = it },
                label = { "$it" },
            )
            VerticalSpacerD500()
            Text(
                text = "${seatCount - 1} bots, you, ${if (seatCount == 2) "heads-up" else "$seatCount seats"}",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacerD800()
            ConfirmPill(label = "Start") { onStart(seatCount) }
        }
    }
}

private fun subtitleFor(seatCount: Int, difficulty: String): String = when (seatCount) {
    2 -> "Heads-up against one $difficulty bot. Fast and aggressive — you're always in the action."
    4 -> "Four-handed $difficulty table. Balanced pace, room to play position."
    6 -> "Full ring with five $difficulty bots. Slower, more strategic — fold more, pick spots."
    else -> "$difficulty table with ${seatCount - 1} bots."
}

@Composable
private fun ConfirmPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(AppTheme.colors.accentPrimary.color)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Heading.H600,
            color = AppTheme.colors.onAccentPrimary,
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun BotTableSetupDialogPreview() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        BotTableSetupDialog(
            difficultyLabel = "Casual",
            onStart = {},
            onDismiss = {},
        )
    }
}

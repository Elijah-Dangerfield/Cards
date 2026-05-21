package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.dialogEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Back-press confirmation for the bot table — surfaces "you'll lose this
 * hand" so the user doesn't drop a hand by accident swiping back. Always
 * shows when a hand is in progress; leaving is a real cost and warrants
 * an explicit confirmation every time.
 */
@Composable
internal fun LeaveBotsConfirmDialog(
    onStay: () -> Unit,
    onLeave: () -> Unit,
) {
    Dialog(
        onDismissRequest = onStay,
        emoji = dialogEmoji(emoji = "🚪", surface = null),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Leave the table?",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "You'll lose progress on this hand. Your XP and chips are saved.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfirmPill(
                    label = "Stay",
                    modifier = Modifier.weight(1f),
                    primary = false,
                    onClick = onStay,
                )
                ConfirmPill(
                    label = "Leave",
                    modifier = Modifier.weight(1f),
                    primary = true,
                    onClick = onLeave,
                )
            }
        }
    }
}

@Composable
private fun ConfirmPill(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (primary) AppTheme.colors.accentPrimary else AppTheme.colors.surfaceSecondary
    val fg = if (primary) AppTheme.colors.onAccentPrimary else AppTheme.colors.onSurfacePrimary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(bg.color)
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            typography = AppTheme.typography.Heading.H600,
            color = fg,
        )
    }
}

@Preview
@Composable
private fun LeaveBotsConfirmDialogPreview() {
    PreviewContent {
        LeaveBotsConfirmDialog(
            onStay = {},
            onLeave = {},
        )
    }
}

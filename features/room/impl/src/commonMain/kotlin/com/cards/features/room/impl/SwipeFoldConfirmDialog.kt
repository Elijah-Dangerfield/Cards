package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonTertiary
import com.dangerfield.cards.libraries.ui.components.checkbox.Checkbox
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
internal fun SwipeFoldConfirmDialog(
    onCancel: () -> Unit,
    onConfirmFold: (dontShowAgain: Boolean) -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(true) }
    Dialog(
        onDismissRequest = onCancel,
        topAccessory = topAccessoryEmoji(emoji = "🃏"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Fold this hand?",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "You'll forfeit the round and any chips already in the pot.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { dontShowAgain = !dontShowAgain }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = dontShowAgain,
                    onCheckedChange = { dontShowAgain = it },
                )
                Text(
                    text = "Don't show this again",
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.onSurfaceSecondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ButtonTertiary(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                ) { Text(text = "Cancel") }
                ButtonPrimary(
                    onClick = { onConfirmFold(dontShowAgain) },
                    modifier = Modifier.weight(1f),
                ) { Text(text = "Fold") }
            }
        }
    }
}

@Preview
@Composable
private fun SwipeFoldConfirmDialogPreview() {
    PreviewContent {
        SwipeFoldConfirmDialog(
            onCancel = {},
            onConfirmFold = {},
        )
    }
}

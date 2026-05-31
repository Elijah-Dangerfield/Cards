package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_leave_bots_body
import cards.libraries.resources.generated.resources.room_leave_bots_leave_button
import cards.libraries.resources.generated.resources.room_leave_bots_stay_button
import cards.libraries.resources.generated.resources.room_leave_bots_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
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
        topAccessory = topAccessoryEmoji(emoji = "🚪"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(Res.string.room_leave_bots_title),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.content,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.room_leave_bots_body),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
                textAlign = TextAlign.Center,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ButtonSecondary(
                    onClick = onStay,
                    modifier = Modifier.weight(1f),
                ) { Text(text = stringResource(Res.string.room_leave_bots_stay_button)) }
                ButtonPrimary(
                    onClick = onLeave,
                    modifier = Modifier
                        .weight(1f),
                ) { Text(text = stringResource(Res.string.room_leave_bots_leave_button)) }
            }
        }
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

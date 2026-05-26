package com.dangerfield.cards.features.room.impl.tutorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonTertiary
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Three-option leave dialog opened when the user taps back on any
 * tutorial step. Replaces PlayPokerScreen's bots-table confirm dialog,
 * which talks about losing XP and chips (irrelevant here).
 *
 * "Back to basics" is the differentiator: a player who skipped the
 * basics and got disoriented in the tableau can land back at the
 * primer in one tap instead of restarting the whole tutorial.
 * Hidden when the player is already on a basics step ([showBackToBasics]
 * = false) so the dialog doesn't offer a no-op self-restart.
 */
@Composable
internal fun TutorialLeaveDialog(
    showBackToBasics: Boolean,
    onBackToBasics: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "🎓"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Leave the tutorial?",
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (showBackToBasics) {
                    "Bail out, or jump back to the basics primer if you need a refresher first."
                } else {
                    "Exit the tutorial? You can restart it anytime from Settings."
                },
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            if (showBackToBasics) {
                // Three-button stack: back-to-basics is the primary
                // action (we're inviting the player back to the
                // safety of the rules), exit is secondary, cancel is
                // tertiary. Order intentional: most-encouraged path
                // at the top, escape hatch at the bottom.
                ButtonPrimary(
                    onClick = onBackToBasics,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back to basics") }
                ButtonSecondary(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Exit tutorial") }
                ButtonTertiary(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            } else {
                // On basics steps there's nothing to "go back to",
                // so the dialog collapses to a two-button choice:
                // exit (primary) or cancel (secondary).
                ButtonPrimary(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Exit tutorial") }
                ButtonSecondary(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }
        }
    }
}

@Preview
@Composable
private fun TutorialLeaveDialogPreview_FromTableau() {
    PreviewContent {
        TutorialLeaveDialog(
            showBackToBasics = true,
            onBackToBasics = {},
            onExit = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun TutorialLeaveDialogPreview_FromBasics() {
    PreviewContent {
        TutorialLeaveDialog(
            showBackToBasics = false,
            onBackToBasics = {},
            onExit = {},
            onDismiss = {},
        )
    }
}

package com.dangerfield.cards.features.room.impl.tutorial

import com.dangerfield.cards.features.room.impl.ui.PlayPokerScreen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.tutorial_leave_dialog_back_to_basics_cta
import cards.libraries.resources.generated.resources.tutorial_leave_dialog_body_already_basics
import cards.libraries.resources.generated.resources.tutorial_leave_dialog_body_with_basics
import cards.libraries.resources.generated.resources.tutorial_leave_dialog_cancel_cta
import cards.libraries.resources.generated.resources.tutorial_leave_dialog_exit_cta
import cards.libraries.resources.generated.resources.tutorial_leave_dialog_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.button.Button
import com.dangerfield.cards.libraries.ui.components.button.ButtonPrimary
import com.dangerfield.cards.libraries.ui.components.button.ButtonSecondary
import com.dangerfield.cards.libraries.ui.components.button.ButtonType
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview

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
        title = stringResource(Res.string.tutorial_leave_dialog_title),
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "🤨"),
    ) {
        // Body slot: the DS owns the title (H800) and body (B500 / contentSecondary
        // / centered) typography, so the copy is bare Text.
        Text(
            text = if (showBackToBasics) {
                stringResource(Res.string.tutorial_leave_dialog_body_with_basics)
            } else {
                stringResource(Res.string.tutorial_leave_dialog_body_already_basics)
            },
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
            ) { Text(stringResource(Res.string.tutorial_leave_dialog_back_to_basics_cta)) }
            ButtonSecondary(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.tutorial_leave_dialog_exit_cta)) }

            Button(
                onClick = onDismiss,
                type = ButtonType.Ghost,
                content = { Text(stringResource(Res.string.tutorial_leave_dialog_cancel_cta)) }
            )
        } else {
            // On basics steps there's nothing to "go back to",
            // so the dialog collapses to a two-button choice:
            // exit (primary) or cancel (secondary).
            ButtonPrimary(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.tutorial_leave_dialog_exit_cta)) }
            ButtonSecondary(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(Res.string.tutorial_leave_dialog_cancel_cta)) }
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

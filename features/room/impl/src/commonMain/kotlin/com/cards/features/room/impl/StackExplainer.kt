package com.dangerfield.cards.features.room.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_stack_explainer_body
import cards.libraries.resources.generated.resources.room_stack_explainer_practice_note
import cards.libraries.resources.generated.resources.room_stack_explainer_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryChipBubble
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Quick-reference modal opened by tapping the user's own chip stack number on
 * the bot table — explains where these chips come from and that they refill
 * on bust, so a new player isn't worried about "losing" them.
 */
@Composable
internal fun StackExplainer(stack: Long, onDismiss: () -> Unit) {
    Dialog(
        title = stringResource(Res.string.room_stack_explainer_title, stack.toString()),
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryChipBubble(),
    ) {
        Text(
            text = stringResource(Res.string.room_stack_explainer_body),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.room_stack_explainer_practice_note),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun StackExplainerPreview() {
    PreviewContent {
        StackExplainer(stack = 1_240, onDismiss = {})
    }
}

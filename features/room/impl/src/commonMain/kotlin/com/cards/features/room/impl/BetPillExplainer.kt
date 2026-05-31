package com.dangerfield.cards.features.room.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_bet_pill_explainer_body
import cards.libraries.resources.generated.resources.room_bet_pill_explainer_title
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryChipBubble
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
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
    Dialog(
        title = stringResource(Res.string.room_bet_pill_explainer_title, seatName, amount.toString()),
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryChipBubble(),
    ) {
        Text(
            text = stringResource(Res.string.room_bet_pill_explainer_body),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview
@Composable
private fun BetPillExplainerPreview() {
    PreviewContent {
        BetPillExplainer(seatName = "Jane", amount = 60, onDismiss = {})
    }
}

package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Small targeted explainer opened by tapping a player's last-action pill
 * ("Folded", "Called 10", etc.). Action-specific copy keeps this less
 * overwhelming than dumping the whole cheat sheet on a single tap.
 */
@Composable
internal fun LastActionExplainer(
    seatName: String,
    action: PlayerAction,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = emojiFor(action)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = headline(seatName, action),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = explainer(action),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Tap the ? icon up top for the full list of actions.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun headline(seatName: String, action: PlayerAction): String = when (action) {
    is PlayerAction.Fold -> "$seatName folded"
    is PlayerAction.Check -> "$seatName checked"
    is PlayerAction.Call -> "$seatName called ${action.amount}"
    is PlayerAction.Bet -> "$seatName bet ${action.amount}"
    is PlayerAction.Raise -> "$seatName raised to ${action.totalStreetContribution}"
    is PlayerAction.AllIn -> "$seatName went all in"
}

private fun emojiFor(action: PlayerAction): String = when (action) {
    is PlayerAction.Fold -> "🏳️"
    is PlayerAction.Check -> "👌"
    is PlayerAction.Call -> "🤝"
    is PlayerAction.Bet -> "💰"
    is PlayerAction.Raise -> "📈"
    is PlayerAction.AllIn -> "🔥"
}

private fun explainer(action: PlayerAction): String = when (action) {
    is PlayerAction.Fold ->
        "Fold means giving up the hand. Any chips already in the pot stay there — they don't come back."
    is PlayerAction.Check ->
        "Check passes the action without putting any chips in. Only legal when there's nothing to call."
    is PlayerAction.Call ->
        "Call matches the current bet so they can stay in the hand. No raise, just keeping up."
    is PlayerAction.Bet ->
        "Bet opens the betting on this street. Everyone else now has to call, raise, or fold."
    is PlayerAction.Raise ->
        "Raise increases the current bet. Everyone else who wants to stay in has to match the new total."
    is PlayerAction.AllIn ->
        "All in means pushing their entire stack into the pot. They can't act again this hand."
}

@Preview
@Composable
private fun LastActionExplainerPreview_Raise() {
    PreviewContent {
        LastActionExplainer(
            seatName = "Jane",
            action = PlayerAction.Raise(totalStreetContribution = 60, raiseAmount = 40),
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun LastActionExplainerPreview_Fold() {
    PreviewContent {
        LastActionExplainer(
            seatName = "Mike",
            action = PlayerAction.Fold,
            onDismiss = {},
        )
    }
}

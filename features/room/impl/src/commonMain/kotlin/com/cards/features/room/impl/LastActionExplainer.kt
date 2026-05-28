package com.dangerfield.cards.features.room.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_last_action_body_all_in
import cards.libraries.resources.generated.resources.room_last_action_body_bet
import cards.libraries.resources.generated.resources.room_last_action_body_call
import cards.libraries.resources.generated.resources.room_last_action_body_check
import cards.libraries.resources.generated.resources.room_last_action_body_fold
import cards.libraries.resources.generated.resources.room_last_action_body_raise
import cards.libraries.resources.generated.resources.room_last_action_cheat_sheet_hint
import cards.libraries.resources.generated.resources.room_last_action_headline_all_in
import cards.libraries.resources.generated.resources.room_last_action_headline_bet
import cards.libraries.resources.generated.resources.room_last_action_headline_call
import cards.libraries.resources.generated.resources.room_last_action_headline_check
import cards.libraries.resources.generated.resources.room_last_action_headline_fold
import cards.libraries.resources.generated.resources.room_last_action_headline_raise
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.gameplay.PlayerAction
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
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
        title = headline(seatName, action),
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = emojiFor(action)),
    ) {
        Text(
            text = stringResource(explainerResourceFor(action)),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.room_last_action_cheat_sheet_hint),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun headline(seatName: String, action: PlayerAction): String = when (action) {
    is PlayerAction.Fold -> stringResource(Res.string.room_last_action_headline_fold, seatName)
    is PlayerAction.Check -> stringResource(Res.string.room_last_action_headline_check, seatName)
    is PlayerAction.Call ->
        stringResource(Res.string.room_last_action_headline_call, seatName, formatThousands(action.amount))
    is PlayerAction.Bet ->
        stringResource(Res.string.room_last_action_headline_bet, seatName, formatThousands(action.amount))
    is PlayerAction.Raise ->
        stringResource(
            Res.string.room_last_action_headline_raise,
            seatName,
            formatThousands(action.totalStreetContribution),
        )
    is PlayerAction.AllIn ->
        stringResource(Res.string.room_last_action_headline_all_in, seatName, formatThousands(action.amount))
}

private fun emojiFor(action: PlayerAction): String = when (action) {
    is PlayerAction.Fold -> "🏳️"
    is PlayerAction.Check -> "👌"
    is PlayerAction.Call -> "🤝"
    is PlayerAction.Bet -> "💰"
    is PlayerAction.Raise -> "📈"
    is PlayerAction.AllIn -> "🔥"
}

private fun explainerResourceFor(action: PlayerAction) = when (action) {
    is PlayerAction.Fold -> Res.string.room_last_action_body_fold
    is PlayerAction.Check -> Res.string.room_last_action_body_check
    is PlayerAction.Call -> Res.string.room_last_action_body_call
    is PlayerAction.Bet -> Res.string.room_last_action_body_bet
    is PlayerAction.Raise -> Res.string.room_last_action_body_raise
    is PlayerAction.AllIn -> Res.string.room_last_action_body_all_in
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

@Preview
@Composable
private fun LastActionExplainerPreview_AllIn() {
    PreviewContent {
        LastActionExplainer(
            seatName = "Mike",
            action = PlayerAction.AllIn(amount = 12_500L),
            onDismiss = {},
        )
    }
}

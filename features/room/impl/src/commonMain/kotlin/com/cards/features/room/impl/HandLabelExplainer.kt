package com.dangerfield.cards.features.room.impl

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.room_hand_label_body_default
import cards.libraries.resources.generated.resources.room_hand_label_body_flush
import cards.libraries.resources.generated.resources.room_hand_label_body_four_of_a_kind
import cards.libraries.resources.generated.resources.room_hand_label_body_full_house
import cards.libraries.resources.generated.resources.room_hand_label_body_high_card
import cards.libraries.resources.generated.resources.room_hand_label_body_pair
import cards.libraries.resources.generated.resources.room_hand_label_body_pocket_pair
import cards.libraries.resources.generated.resources.room_hand_label_body_royal_flush
import cards.libraries.resources.generated.resources.room_hand_label_body_straight
import cards.libraries.resources.generated.resources.room_hand_label_body_straight_flush
import cards.libraries.resources.generated.resources.room_hand_label_body_three_of_a_kind
import cards.libraries.resources.generated.resources.room_hand_label_body_two_pair
import cards.libraries.resources.generated.resources.room_hand_label_cheat_sheet_hint
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.dialog.topAccessoryEmoji
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Small targeted explainer opened by tapping the human's hand label
 * ("High Card", "Pair", "Two Pair", etc.) on the player tile. Shows a
 * one-line description of THAT category and nudges the user toward the
 * cheat sheet for the full list of rankings.
 */
@Composable
internal fun HandLabelExplainer(
    label: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        title = label,
        onDismissRequest = onDismiss,
        topAccessory = topAccessoryEmoji(emoji = "🃏"),
    ) {
        Text(
            text = stringResource(explainerResourceFor(label)),
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.room_hand_label_cheat_sheet_hint),
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.onSurfaceSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Maps the hand label to a brief explanation. Matched on prefix (the engine
 * may suffix with details like "Pocket pair", "High card · Ace") so the
 * matching is generous.
 */
private fun explainerResourceFor(label: String) = when {
    label.startsWith("royal flush", ignoreCase = true) -> Res.string.room_hand_label_body_royal_flush
    label.startsWith("straight flush", ignoreCase = true) -> Res.string.room_hand_label_body_straight_flush
    label.startsWith("four of a kind", ignoreCase = true) -> Res.string.room_hand_label_body_four_of_a_kind
    label.startsWith("full house", ignoreCase = true) -> Res.string.room_hand_label_body_full_house
    label.startsWith("flush", ignoreCase = true) -> Res.string.room_hand_label_body_flush
    label.startsWith("straight", ignoreCase = true) -> Res.string.room_hand_label_body_straight
    label.startsWith("three of a kind", ignoreCase = true) -> Res.string.room_hand_label_body_three_of_a_kind
    label.startsWith("two pair", ignoreCase = true) -> Res.string.room_hand_label_body_two_pair
    label.startsWith("pocket pair", ignoreCase = true) -> Res.string.room_hand_label_body_pocket_pair
    label.startsWith("pair", ignoreCase = true) -> Res.string.room_hand_label_body_pair
    label.startsWith("high card", ignoreCase = true) -> Res.string.room_hand_label_body_high_card
    else -> Res.string.room_hand_label_body_default
}

@Preview
@Composable
private fun HandLabelExplainerPreview() {
    PreviewContent {
        HandLabelExplainer(label = "Two pair · Aces & Kings", onDismiss = {})
    }
}

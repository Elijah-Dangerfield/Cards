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
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.dialog.Dialog
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
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
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = label,
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.onSurfacePrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = explainerFor(label),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Tap the ? icon up top for the full ranking list with examples.",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.onSurfaceSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Maps the hand label to a brief explanation. Matched on prefix (the engine
 * may suffix with details like "Pocket pair", "High card · Ace") so the
 * matching is generous.
 */
private fun explainerFor(label: String): String {
    val l = label.lowercase()
    return when {
        l.startsWith("royal flush") ->
            "Ten through Ace, all the same suit. The strongest hand in poker."
        l.startsWith("straight flush") ->
            "Five cards in a row, all the same suit. Above four of a kind."
        l.startsWith("four of a kind") ->
            "Four cards of the same rank. Beats every hand except a straight flush or royal flush."
        l.startsWith("full house") ->
            "Three of a kind plus a pair. Beats a flush."
        l.startsWith("flush") ->
            "Five cards of the same suit, in any order. Beats a straight."
        l.startsWith("straight") ->
            "Five cards in a row, mixed suits. Beats three of a kind."
        l.startsWith("three of a kind") ->
            "Three cards of the same rank. Beats two pair."
        l.startsWith("two pair") ->
            "Two pairs of different ranks. Beats a single pair."
        l.startsWith("pocket pair") ->
            "Your two hole cards are the same rank — already a pair before any community cards. Strong starting hand."
        l.startsWith("pair") ->
            "Two cards of the same rank. Beats a high card."
        l.startsWith("high card") ->
            "No combinations — the highest single card wins. The weakest hand category."
        else ->
            "Your current hand, based on your hole cards and what's on the board."
    }
}

@Preview
@Composable
private fun HandLabelExplainerPreview() {
    PreviewContent {
        HandLabelExplainer(label = "Two pair · Aces & Kings", onDismiss = {})
    }
}

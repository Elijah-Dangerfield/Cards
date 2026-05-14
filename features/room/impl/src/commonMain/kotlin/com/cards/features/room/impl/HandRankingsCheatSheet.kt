package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BottomSheet
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

private val rankings: List<Pair<String, String>> = listOf(
    "Royal Flush" to "A K Q J 10 of the same suit",
    "Straight Flush" to "Five in sequence, all same suit",
    "Four of a Kind" to "Four cards of the same rank",
    "Full House" to "Three of one rank plus a pair",
    "Flush" to "Five cards of the same suit",
    "Straight" to "Five in sequence (any suit)",
    "Three of a Kind" to "Three cards of the same rank",
    "Two Pair" to "Two pairs of different ranks",
    "Pair" to "Two cards of the same rank",
    "High Card" to "Highest card if no other hand made",
)

@Composable
fun HandRankingsCheatSheet(onDismiss: () -> Unit) {
    BottomSheet(
        onDismissRequest = onDismiss,
        showDragHandle = true,
        backgroundColor = AppTheme.colors.surfacePrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Hand rankings",
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.onSurfacePrimary,
            )
            Spacer(modifier = Modifier.height(12.dp))
            rankings.forEach { (name, description) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = name,
                            typography = AppTheme.typography.Body.B500,
                            color = AppTheme.colors.onSurfacePrimary,
                        )
                        Text(
                            text = description,
                            typography = AppTheme.typography.Body.B400,
                            color = AppTheme.colors.onSurfaceSecondary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

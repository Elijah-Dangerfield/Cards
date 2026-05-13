package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(AppTheme.colors.surfaceSecondary.color)
                .padding(20.dp)
                .clickable(enabled = false, onClick = {}),
        ) {
            Text(
                text = "Hand rankings",
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.text,
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
                            color = AppTheme.colors.text,
                        )
                        Text(
                            text = description,
                            typography = AppTheme.typography.Body.B400,
                            color = AppTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

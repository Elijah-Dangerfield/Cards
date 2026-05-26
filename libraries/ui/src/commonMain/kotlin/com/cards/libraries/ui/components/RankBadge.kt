package com.dangerfield.cards.libraries.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii

@Composable
fun RankBadge(
    rank: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(Radii.Round.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF8E7CC3), Color(0xFFE07AB1)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "♛",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            // 0 means "not yet ranked" (anonymous or no multiplayer games yet).
            // The sheet behind this badge explains the path to a real rating.
            text = if (rank <= 0) "Unranked" else "Rank $rank",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

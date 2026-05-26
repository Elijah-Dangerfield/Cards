package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.poker.LocalFeltAccentSurface
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme

/**
 * Tiny "57% win" pill rendered just above the player area when the user
 * has the [Win Odds Display](tool_win_odds) utility equipped. Pure UI;
 * the percentage is computed in [PlayPokerViewModel] via Monte Carlo
 * over random opponent hands + remaining board, throttled on input
 * change so it doesn't recompute on every state tick.
 *
 * Color-coded by the typical "equity feeling" thresholds — a player who
 * looks down at "12%" knows to fold without doing the math; "73%" calls
 * for value. We deliberately resist the urge to over-design (no animated
 * meter, no historical chart) — it's a heads-up display, not a
 * dashboard.
 */
@Composable
fun WinOddsBadge(
    winPercent: Int,
    modifier: Modifier = Modifier,
) {
    val pillTone = when {
        winPercent >= 70 -> AppTheme.colors.status.okay
        winPercent >= 40 -> AppTheme.colors.status.warning
        else -> AppTheme.colors.status.bad
    }
    val pillBackground = LocalFeltAccentSurface.current ?: AppTheme.colors.surfaceSecondary.color
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(pillBackground)
            .border(
                width = 1.dp,
                color = pillTone.color,
                shape = RoundedCornerShape(50),
            )
            .padding(PaddingValues(horizontal = 12.dp, vertical = 4.dp)),
    ) {
        Text(
            text = "📊",
            typography = AppTheme.typography.Body.B400,
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = "$winPercent% win",
            typography = AppTheme.typography.Body.B400,
            color = pillTone,
        )
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun WinOddsBadgePreview_Strong() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        WinOddsBadge(winPercent = 73)
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun WinOddsBadgePreview_Coin() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        WinOddsBadge(winPercent = 52)
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun WinOddsBadgePreview_Weak() {
    com.dangerfield.cards.libraries.ui.PreviewContent {
        WinOddsBadge(winPercent = 12)
    }
}

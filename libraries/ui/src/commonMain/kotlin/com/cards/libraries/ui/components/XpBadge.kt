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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Lifetime XP indicator. Pair with [RankBadge] (skill) and [ChipBadge] (currency)
 * — these three pills together form the canonical "progression header" pattern
 * in profile/home/lobby views.
 *
 * XP only goes up. There's no decreasing or "broke" state, so the visual is
 * intentionally calm and consistent.
 */
@Composable
fun XpBadge(
    xp: Long,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
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
                        listOf(Color(0xFF4FC3F7), Color(0xFF66BB6A)),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✦",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "${formatThousands(xp)} XP",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.text,
        )
    }
}

@Preview
@Composable
private fun XpBadgePreview_Small() {
    PreviewContent {
        XpBadge(xp = 240)
    }
}

@Preview
@Composable
private fun XpBadgePreview_Large() {
    PreviewContent {
        XpBadge(xp = 1_234_567)
    }
}

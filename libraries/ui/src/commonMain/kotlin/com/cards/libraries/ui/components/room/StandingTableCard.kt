package com.dangerfield.cards.libraries.ui.components.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StandingTableCard(
    name: String,
    schedule: String,
    status: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLive: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(Radii.Card.shape)
            .background(AppTheme.colors.surface.color)
            .border(1.dp, AppTheme.colors.border.color, Radii.Card.shape)
            .clickable(onClick = onClick).padding(Dimension.D600),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimension.D300),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimension.D200)) {
            Text(name, typography = AppTheme.typography.Label.L500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(schedule, typography = AppTheme.typography.Caption.C300, color = AppTheme.colors.contentSecondary)
        }
        Text(status, typography = AppTheme.typography.Label.L300, color = if (isLive) AppTheme.colors.accentPrimary else AppTheme.colors.contentSecondary)
    }
}

@Preview
@Composable
private fun StandingTableCardPreview_Tonight() {
    PreviewContent { StandingTableCard("Thursday Night Poker", "Thursdays · 8:00 PM", "Tonight", {}) }
}

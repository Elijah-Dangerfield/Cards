package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import com.dangerfield.cards.system.VerticalSpacerD200
import com.dangerfield.cards.system.VerticalSpacerD500

/**
 * Horizontal scroll of the user's most recent achievement unlocks
 * with a header chevron to the full Achievements grid. Tap a tile
 * → open that achievement's detail (in V1 routes through to the
 * Achievements page; per-achievement detail is V1.x).
 *
 * Returns nothing when [items] is empty — Home doesn't push an
 * empty "go earn one!" state. Once the user has at least one, this
 * surfaces as a quiet brag-shelf up top. Real source wires into
 * `ProgressionRepository.recentUnlocks()` (todo); fake data for
 * V1 keeps the surface honest while the wiring lands.
 */
@Composable
internal fun RecentAchievementsStrip(
    items: List<RecentAchievement>,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSeeAll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Recent unlocks",
                typography = AppTheme.typography.Heading.H500,
                color = AppTheme.colors.text,
            )
            Text(
                text = "See all",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
        VerticalSpacerD500()
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Dimension.D500),
        ) {
            items(items = items, key = { it.id }) { item ->
                AchievementTile(item = item, onClick = onSeeAll)
            }
        }
    }
}

@Composable
private fun AchievementTile(item: RecentAchievement, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(TILE_WIDTH)
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfaceSecondary.color)
            .border(
                width = 1.dp,
                color = item.rarityColor.copy(alpha = 0.45f),
                shape = Radii.R700.shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Dimension.D400, vertical = Dimension.D500),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(item.rarityColor.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = item.icon,
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.text,
            )
        }
        VerticalSpacerD200()
        Text(
            text = item.name,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Immutable
internal data class RecentAchievement(
    val id: String,
    val name: String,
    val icon: String,
    val rarityColor: Color,
)

private val TILE_WIDTH = 110.dp

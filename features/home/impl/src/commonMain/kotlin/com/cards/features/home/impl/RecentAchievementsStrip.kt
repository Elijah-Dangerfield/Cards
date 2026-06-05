package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_recent_achievements_section_title
import cards.libraries.resources.generated.resources.home_recent_achievements_see_all
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.EdgeToEdgeRow
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedalWithDetail
import com.dangerfield.cards.libraries.ui.components.achievement.MedalSize
import com.dangerfield.cards.libraries.ui.components.header.SectionHeader
import com.dangerfield.cards.system.VerticalSpacerD500
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Horizontal scroll of the user's most recent achievement unlocks
 * with a header chevron to the full Achievements grid. Renders the
 * same [AchievementMedal] the Stats / Achievements pages use so the
 * brag-shelf reads as a window into those surfaces, not its own
 * bespoke treatment.
 *
 * Returns nothing when [items] is empty — Home doesn't push an empty
 * "go earn one!" state. Tap routes to the full Achievements grid —
 * Home is a launcher, not a viewer.
 */
@Composable
internal fun RecentAchievementsStrip(
    items: List<RecentAchievement>,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = stringResource(Res.string.home_recent_achievements_section_title),
            trailingLabel = stringResource(Res.string.home_recent_achievements_see_all),
            onClick = onSeeAll,
        )
        VerticalSpacerD500()
        EdgeToEdgeRow {
            items(items = items, key = { it.achievement.id.name }) { item ->
                // Tapping a recent achievement opens the same detail sheet the
                // Profile / Achievements grids use (it's a shared DS component);
                // the header chevron still routes to the full grid.
                AchievementMedalWithDetail(
                    achievement = item.achievement,
                    earnedAtEpochMs = item.earnedAtEpochMs,
                    progress = item.achievement.criterion.target,
                    size = MedalSize.Small,
                )
            }
        }
    }
}

/**
 * Home-shelf carrier for a recently-earned achievement. Holds the full
 * [Achievement] so the rendering can reuse the shared
 * [AchievementMedal] (which needs the rarity, icon, etc.) instead of a
 * stripped-down UI model.
 */
@Immutable
data class RecentAchievement(
    val achievement: Achievement,
    val earnedAtEpochMs: Long,
)

// ---------------------------------------------------------------------------
// Previews — empty state (auto-hide), a small ribbon of earned tiles, and
// a fuller four-tile row so we can eyeball scroll spacing.
// ---------------------------------------------------------------------------

@Preview
@Composable
private fun RecentAchievementsStripPreview_ThreeEarned() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        RecentAchievementsStrip(
            items = sampleRecentAchievements(count = 3),
            onSeeAll = {},
        )
    }
}

@Preview
@Composable
private fun RecentAchievementsStripPreview_OverflowsScroll() {
    PreviewContent(contentPadding = PaddingValues(16.dp)) {
        RecentAchievementsStrip(
            items = sampleRecentAchievements(count = 6),
            onSeeAll = {},
        )
    }
}

private fun sampleRecentAchievements(count: Int): List<RecentAchievement> {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val day = 24L * 60L * 60L * 1000L
    return AllAchievements
        .filter { !it.isMystery }
        .take(count)
        .mapIndexed { index, achievement ->
            RecentAchievement(
                achievement = achievement,
                earnedAtEpochMs = now - (index + 1) * day,
            )
        }
}

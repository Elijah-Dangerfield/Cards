package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.Achievement
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun AchievementsScreenContent(
    state: AchievementsState,
    modifier: Modifier = Modifier,
) {
    val earned = state.progress.earned.size
    val total = AllAchievements.size

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "$earned of $total earned",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(AllAchievements, key = { it.id }) { achievement ->
                AchievementMedallion(
                    achievement = achievement,
                    earnedAtEpochMs = state.progress.earned[achievement.id],
                    progress = state.progress.counters[achievement.id] ?: 0,
                )
            }
        }
    }
}

@Preview
@Composable
private fun AchievementsScreenContentPreview() {
    PreviewContent {
        AchievementsScreenContent(
            state = AchievementsState(
                isLoading = false,
                progress = AchievementProgress(
                    earned = mapOf(
                        AllAchievements[0].id to kotlin.time.Clock.System.now().toEpochMilliseconds(),
                        AllAchievements[5].id to kotlin.time.Clock.System.now().toEpochMilliseconds(),
                    ),
                    counters = mapOf(
                        AllAchievements[1].id to 23,
                    ),
                    customCounters = emptyMap(),
                ),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

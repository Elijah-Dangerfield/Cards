package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.achievements_earned_count
import cards.libraries.resources.generated.resources.achievements_locked_label
import cards.libraries.resources.generated.resources.achievements_title
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.currentProgress
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedalWithDetail
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD500
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun AchievementsScreen(
    state: AchievementsState,
    onBack: () -> Unit,
) {
    val earned = state.progress.earned.size
    val total = AllAchievements.size
    val gridState = rememberLazyGridState()

    Screen(
        topBar = {
            TopBar(
                title = stringResource(Res.string.achievements_title),
                onNavigateBack = onBack,
                scrollState = gridState,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .screenContentPadding(paddingValues = padding),
        ) {

            VerticalSpacerD500()

            Text(
                text = stringResource(Res.string.achievements_earned_count, earned, total),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(AllAchievements, key = { it.id }) { achievement ->
                    val earned = state.progress.isEarned(achievement.id)
                    val isMystery = achievement.isMystery && !earned
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AchievementMedalWithDetail(
                            achievement = achievement,
                            earnedAtEpochMs = state.progress.earned[achievement.id],
                            progress = achievement.currentProgress(state.progress),
                            modifier = Modifier.fillMaxWidth(0.82f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isMystery) {
                                stringResource(Res.string.achievements_locked_label)
                            } else {
                                achievement.name
                            },
                            typography = AppTheme.typography.Label.L400,
                            color = if (earned) {
                                AppTheme.colors.content
                            } else {
                                AppTheme.colors.contentSecondary
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun AchievementsScreenPreview_Empty() {
    PreviewContent {
        AchievementsScreen(
            state = AchievementsState(
                isLoading = false,
                progress = AchievementProgress.Empty,
            ),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun AchievementsScreenPreview_SomeEarned() {
    PreviewContent {
        AchievementsScreen(
            state = AchievementsState(
                isLoading = false,
                progress = AchievementProgress(
                    earned = mapOf(
                        AllAchievements[0].id to Clock.System.now().toEpochMilliseconds(),
                        AllAchievements[5].id to Clock.System.now().toEpochMilliseconds(),
                    ),
                    counters = mapOf(
                        AllAchievements[1].id to 23,
                    ),
                    customCounters = emptyMap(),
                ),
            ),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun AchievementsScreenPreview_AllEarned() {
    val now = Clock.System.now().toEpochMilliseconds()
    PreviewContent {
        AchievementsScreen(
            state = AchievementsState(
                isLoading = false,
                progress = AchievementProgress(
                    earned = AllAchievements.associate { it.id to now },
                    counters = emptyMap(),
                    customCounters = emptyMap(),
                ),
            ),
            onBack = {},
        )
    }
}

package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.ui.system.color.LevelProgressGradient
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.LevelProgressBar
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedallion
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Radii
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StatsScreen(
    state: StatsState,
    onBack: () -> Unit,
    onSeeAllAchievements: () -> Unit = {},
    onShowExplainers: () -> Unit = {},
) {
    val levelProgress = remember(state.progression.totalXp) {
        levelProgressFor(state.progression.totalXp)
    }
    val scrollState = rememberScrollState()
    Screen(
        topBar = {
            TopBar(
                title = "Stats",
                onNavigateBack = onBack,
                scrollState = scrollState,
                actions = {
                    IconButton(
                        icon = Icons.Question("Hand info and rankings"),
                        onClick = onShowExplainers,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .screenContentPadding(paddingValues = padding),
        ) {
            XpHero(progress = levelProgress)
            Spacer(modifier = Modifier.height(24.dp))

            SectionTitle("Lifetime")
            Spacer(modifier = Modifier.height(8.dp))
            LifetimeStatsGrid(progression = state.progression)
            Spacer(modifier = Modifier.height(24.dp))

            AchievementsHighlights(
                progress = state.achievements,
                onSeeAll = onSeeAllAchievements,
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (state.recentEvents.isNotEmpty()) {
                SectionTitle("Recent XP")
                Spacer(modifier = Modifier.height(8.dp))
                RecentEventsList(events = state.recentEvents)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun XpHero(progress: LevelProgress) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(LevelProgressGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = progress.level.toString(),
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Level ${progress.level}",
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${formatThousands(progress.totalXp)} XP",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        LevelProgressBar(fraction = progress.fraction, height = 10.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${formatThousands(progress.xpToNextLevel)} XP to level ${progress.level + 1}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun LifetimeStatsGrid(progression: Progression) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Hands played",
                value = formatThousands(progression.handsPlayed),
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Hands won",
                value = formatThousands(progression.handsWon),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Folds",
                value = formatThousands(progression.handsFolded),
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Showdown losses",
                value = formatThousands(progression.handsLostAtShowdown),
            )
        }
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Column(
        modifier = modifier
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfacePrimary.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = value,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun RecentEventsList(events: List<XpEvent>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surfacePrimary.color),
    ) {
        events.forEachIndexed { index, event ->
            EventRow(event)
            if (index != events.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(AppTheme.colors.border.color),
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: XpEvent) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sourceLabel(event.source),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
            // Subline disambiguates the row: hand id for hand-derived XP,
            // achievement name for achievement unlocks. Without this the feed
            // says "Achievement unlocked" without ever telling the user which
            // one popped.
            val subline = when {
                event.description != null -> event.description
                event.handId != null -> "Hand #${event.handId} · ${modeLabel(event.mode)}"
                else -> null
            }
            subline?.let {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = it,
                    typography = AppTheme.typography.Body.B400,
                    color = AppTheme.colors.textSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "+${event.deltaXp}",
            typography = AppTheme.typography.Body.B600,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun AchievementsHighlights(
    progress: AchievementProgress,
    onSeeAll: () -> Unit,
) {
    // Always fill 3 slots. Lead with most-recently-earned, then back-fill
    // with locked achievements as a "what's next" preview — otherwise the
    // medallion's aspectRatio(1f) blows it up to full-row width when only
    // one is earned. See [AchievementMedallion].
    val slotCount = 3
    val earnedOrdered: List<Pair<com.dangerfield.cards.libraries.cards.Achievement, Long?>> =
        progress.earned.entries
            .sortedByDescending { it.value }
            .mapNotNull { (id, ts) -> AllAchievementsById[id]?.let { it to (ts as Long?) } }
    val preview: List<Pair<com.dangerfield.cards.libraries.cards.Achievement, Long?>> =
        AllAchievements
            .filter { it.id !in progress.earned.keys }
            .map { it to (null as Long?) }
    val toShow = (earnedOrdered + preview).take(slotCount)
    val total = AllAchievements.size
    val earnedCount = progress.earned.size

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Achievements",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$earnedCount / $total",
                typography = AppTheme.typography.Body.B400,
                color = AppTheme.colors.textSecondary,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            toShow.forEach { (achievement, earnedAt) ->
                AchievementMedallion(
                    achievement = achievement,
                    earnedAtEpochMs = earnedAt,
                    progress = progress.counters[achievement.id] ?: 0,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.Round.shape)
                .background(AppTheme.colors.surfacePrimary.color)
                .clickable(onClick = onSeeAll)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "See all $total achievements",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.text,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.text,
    )
}

private fun sourceLabel(source: XpSource): String = when (source) {
    XpSource.BASE -> "Hand finished"
    XpSource.INVESTMENT -> "Chips committed"
    XpSource.SHOWDOWN -> "Reached showdown"
    XpSource.HAND_STRENGTH -> "Hand strength"
    XpSource.ACHIEVEMENT -> "Achievement unlocked"
}

private fun modeLabel(mode: XpMode): String = when (mode) {
    XpMode.BOTS -> "Bots"
    XpMode.MULTIPLAYER -> "Multiplayer"
}


@Preview
@Composable
private fun StatsScreenPreview_Loading() {
    PreviewContent {
        StatsScreen(
            state = StatsState(),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun StatsScreenPreview_Empty() {
    PreviewContent {
        StatsScreen(
            state = StatsState(isLoading = false),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun StatsScreenPreview_Populated() {
    PreviewContent {
        StatsScreen(
            state = StatsState(
                isLoading = false,
                progression = Progression(
                    totalXp = 2_840,
                    handsPlayed = 412,
                    handsWon = 110,
                    handsFolded = 220,
                    handsLostAtShowdown = 82,
                    botHandsPlayed = 412,
                    updatedAtEpochMs = 0,
                ),
                recentEvents = listOf(
                    XpEvent(id = 1, deltaXp = 5, source = XpSource.BASE, mode = XpMode.BOTS, handId = "42", createdAtEpochMs = 0L),
                    XpEvent(id = 2, deltaXp = 3, source = XpSource.INVESTMENT, mode = XpMode.BOTS, handId = "42", createdAtEpochMs = 0L),
                    XpEvent(id = 3, deltaXp = 5, source = XpSource.SHOWDOWN, mode = XpMode.BOTS, handId = "42", createdAtEpochMs = 0L),
                    XpEvent(id = 4, deltaXp = 6, source = XpSource.HAND_STRENGTH, mode = XpMode.BOTS, handId = "42", createdAtEpochMs = 0L),
                ),
            ),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun StatsScreenPreview_SingleAchievementEarned() {
    // Pins the bug we hit when only one achievement was earned: the
    // medallion's aspectRatio(1f) made it fill the whole row. The highlights
    // strip now back-fills with locked previews so the layout stays the
    // same 3-up at any earned count.
    PreviewContent {
        val earnedId = AllAchievements.first().id
        StatsScreen(
            state = StatsState(
                isLoading = false,
                progression = Progression(
                    totalXp = 320,
                    handsPlayed = 14,
                    handsWon = 3,
                    handsFolded = 8,
                    handsLostAtShowdown = 3,
                    botHandsPlayed = 14,
                    updatedAtEpochMs = 0,
                ),
                achievements = AchievementProgress(
                    earned = mapOf(earnedId to 0L),
                    counters = emptyMap(),
                    customCounters = emptyMap(),
                ),
            ),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun StatsScreenPreview_WithAchievements() {
    PreviewContent {
        val earnedIds = AllAchievements.take(3).map { it.id }
        StatsScreen(
            state = StatsState(
                isLoading = false,
                progression = Progression(
                    totalXp = 6_120,
                    handsPlayed = 980,
                    handsWon = 260,
                    handsFolded = 500,
                    handsLostAtShowdown = 220,
                    botHandsPlayed = 980,
                    updatedAtEpochMs = 0,
                ),
                achievements = AchievementProgress(
                    earned = earnedIds.associateWith { 0L },
                    counters = emptyMap(),
                    customCounters = emptyMap(),
                ),
            ),
            onBack = {},
        )
    }
}

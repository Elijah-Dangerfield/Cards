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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedallion
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.text.Text
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


                    IconButton(onClick = onShowExplainers) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Hand info and rankings",
                            tint = AppTheme.colors.text.color,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState),
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
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4FC3F7), Color(0xFF66BB6A)),
                    ),
                ),
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
        LevelProgressBar(progress = progress)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${formatThousands(progress.xpToNextLevel)} XP to level ${progress.level + 1}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun LevelProgressBar(progress: LevelProgress) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(AppTheme.colors.surfacePrimary.color),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.fraction)
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4FC3F7), Color(0xFF66BB6A)),
                    ),
                ),
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
    // Show the 3 most-recently-earned achievements on the XP page so progress
    // feels visible at a glance. If nothing's earned yet, surface 3 "easy
    // wins" instead so the section never reads as empty.
    val earnedOrdered = progress.earned.entries
        .sortedByDescending { it.value }
        .mapNotNull { (id, ts) -> AllAchievementsById[id]?.let { it to ts } }
    val toShow = if (earnedOrdered.isNotEmpty()) {
        earnedOrdered.take(3).map { (ach, ts) -> ach to ts }
    } else {
        AllAchievements.take(3).map { it to (null as Long?) }
    }
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
                .clip(RoundedCornerShape(50))
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

private fun formatThousands(value: Long): String {
    val s = value.toString()
    if (s.length <= 3) return s
    val withCommas = StringBuilder()
    val len = s.length
    for (i in 0 until len) {
        if (i > 0 && (len - i) % 3 == 0) withCommas.append(',')
        withCommas.append(s[i])
    }
    return withCommas.toString()
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

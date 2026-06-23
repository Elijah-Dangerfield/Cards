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
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.stats_play_style_empty_blurb
import cards.libraries.resources.generated.resources.stats_play_style_empty_title
import cards.libraries.resources.generated.resources.stats_recent_xp_boosted_tag
import cards.libraries.resources.generated.resources.stats_play_style_section
import com.dangerfield.cards.libraries.cards.AchievementProgress
import com.dangerfield.cards.libraries.cards.AllAchievements
import com.dangerfield.cards.libraries.cards.AllAchievementsById
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.ui.system.color.LevelProgressGradient
import com.dangerfield.cards.libraries.cards.PlayStyleAxes
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.system.LocalLevelCurve
import com.dangerfield.cards.libraries.ui.components.LevelProgressBar
import com.dangerfield.cards.libraries.ui.components.PlayStyleRadarMark
import com.dangerfield.cards.libraries.ui.components.PlayingStyleCard
import com.dangerfield.cards.libraries.ui.components.SaveProgressBanner
import com.dangerfield.cards.libraries.ui.components.toRadarAxes
import com.dangerfield.cards.libraries.ui.components.toStyleCopy
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.XpBoostBadge
import com.dangerfield.cards.libraries.cards.currentProgress
import com.dangerfield.cards.libraries.ui.components.achievement.AchievementMedalWithDetail
import com.dangerfield.cards.libraries.ui.components.achievement.MedalSize
import com.dangerfield.cards.libraries.ui.components.achievement.earnedAgo
import com.dangerfield.cards.libraries.ui.components.achievement.label
import com.dangerfield.cards.libraries.ui.components.header.TopBar
import com.dangerfield.cards.libraries.ui.components.icon.IconButton
import com.dangerfield.cards.libraries.ui.components.icon.Icons
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.Radii
import kotlin.math.roundToInt
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun StatsScreen(
    state: StatsState,
    onBack: () -> Unit,
    onSeeAllAchievements: () -> Unit = {},
    onShowExplainers: () -> Unit = {},
    onClaimAccount: () -> Unit = {},
) {
    val levelCurve = LocalLevelCurve.current
    val levelProgress = remember(state.progression.totalXp, levelCurve) {
        levelProgressFor(state.progression.totalXp, levelCurve)
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
            state.xpBoostExpiresAtEpochMs?.let { expiry ->
                Spacer(modifier = Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    XpBoostBadge(expiresAtEpochMs = expiry)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))

            if (state.isAnonymous) {
                SaveProgressBanner(onSignIn = onClaimAccount)
                Spacer(modifier = Modifier.height(24.dp))
            }

            SectionTitle("Lifetime")
            Spacer(modifier = Modifier.height(8.dp))
            LifetimeStatsGrid(progression = state.progression)
            Spacer(modifier = Modifier.height(24.dp))

            SectionTitle(stringResource(Res.string.stats_play_style_section))
            Spacer(modifier = Modifier.height(8.dp))
            val style = state.playStyle
            if (style != null && style.hasEnoughData) {
                val copy = remember(style) { style.toStyleCopy() }
                PlayingStyleCard(
                    axes = style.toRadarAxes(),
                    styleName = stringResource(copy.label),
                    description = stringResource(copy.description),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // Below the sample threshold the derived shape is noise — show a
                // "keep playing" teaser instead of a misleading blob.
                PlayStyleEmptyCard(modifier = Modifier.fillMaxWidth())
            }
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
                color = AppTheme.colors.content,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Level ${progress.level}",
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "${formatThousands(progress.totalXp)} XP",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.contentSecondary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        LevelProgressBar(
            fraction = progress.fraction,
            faceColor = AppTheme.colors.accentSecondary,
            deepColor = AppTheme.colors.accentSecondaryDeep,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${formatThousands(progress.xpToNextLevel)} XP to level ${progress.level + 1}",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun LifetimeStatsGrid(progression: Progression) {
    val played = progression.handsPlayed
    val winRate = percentOf(progression.handsWon, played)
    val foldRate = percentOf(progression.handsFolded, played)
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
                label = "Win rate",
                value = winRate,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = "Fold rate",
                value = foldRate,
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

/**
 * A rounded whole-percent string for [part] of [whole] ("26%"), or an em-free
 * "-" dash when there's no denominator yet so a brand-new player doesn't read a
 * misleading "0%".
 */
private fun percentOf(part: Long, whole: Long): String =
    if (whole <= 0L) "-" else "${(part * 100.0 / whole).roundToInt()}%"

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Column(
        modifier = modifier
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = value,
            typography = AppTheme.typography.Heading.H700,
            color = AppTheme.colors.content,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.contentSecondary,
        )
    }
}

@Composable
private fun RecentEventsList(events: List<XpEvent>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Radii.R700.shape)
            .background(AppTheme.colors.surface.color),
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
        Text(
            text = sourceEmoji(event.source),
            typography = AppTheme.typography.Heading.H600,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sourceLabel(event.source),
                typography = AppTheme.typography.Body.B600.SemiBold,
                color = AppTheme.colors.content,
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
                    typography = AppTheme.typography.Body.B500,
                    color = AppTheme.colors.contentSecondary,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            // Flag boosted hand rows so the doubled amount reads as a reward
            // rather than a mystery — the deltaXp already holds the 2× total.
            if (event.wasBoosted) {
                Text(
                    text = stringResource(Res.string.stats_recent_xp_boosted_tag),
                    typography = AppTheme.typography.Caption.C200.SemiBold,
                    color = AppTheme.colors.accentSecondary,
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = "+${event.deltaXp}",
                typography = AppTheme.typography.Heading.H600,
                color = sourceColor(event.source),
            )
            val ago = earnedAgo(
                event.createdAtEpochMs,
                Clock.System.now().toEpochMilliseconds(),
            ).label()
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = ago,
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentTertiary,
            )
        }
    }
}

@Composable
private fun AchievementsHighlights(
    progress: AchievementProgress,
    onSeeAll: () -> Unit,
) {
    // Always fill 3 slots. Lead with most-recently-earned, then back-fill
    // with locked achievements as a "what's next" preview — otherwise the
    // medal's aspectRatio(1f) blows it up to full-row width when only
    // one is earned. See [AchievementMedal].
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
                typography = AppTheme.typography.Heading.H600,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "$earnedCount / $total",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            toShow.forEach { (achievement, earnedAt) ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AchievementMedalWithDetail(
                        achievement = achievement,
                        earnedAtEpochMs = earnedAt,
                        progress = achievement.currentProgress(progress),
                        size = MedalSize.Small,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Radii.Round.shape)
                .background(AppTheme.colors.surface.color)
                .clickable(onClick = onSeeAll)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "See all $total achievements",
                typography = AppTheme.typography.Body.B600.SemiBold,
                color = AppTheme.colors.content,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Heading.H600,
        color = AppTheme.colors.content,
    )
}

/**
 * Shown in the Play-style slot until the user has played [PlayStyleAxes.MIN_SAMPLE]
 * hands. Reuses the decorative [PlayStyleRadarMark] so it reads as the same
 * radar, paired with a "keep playing" nudge instead of a misleading shape.
 */
@Composable
private fun PlayStyleEmptyCard(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(Radii.Card.shape)
            .background(AppTheme.colors.surface.color)
            .padding(Dimension.D500),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.stats_play_style_empty_title),
                typography = AppTheme.typography.Body.B600,
                color = AppTheme.colors.content,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    Res.string.stats_play_style_empty_blurb,
                    PlayStyleAxes.MIN_SAMPLE,
                ),
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
        Spacer(modifier = Modifier.width(Dimension.D400))
        PlayStyleRadarMark(size = 44.dp)
    }
}

private fun sourceLabel(source: XpSource): String = when (source) {
    XpSource.BASE -> "Hand finished"
    XpSource.INVESTMENT -> "Chips committed"
    XpSource.SHOWDOWN -> "Reached showdown"
    XpSource.HAND_STRENGTH -> "Hand strength"
    XpSource.ACHIEVEMENT -> "Achievement unlocked"
}

private fun sourceEmoji(source: XpSource): String = when (source) {
    XpSource.BASE -> "🃏"
    XpSource.INVESTMENT -> "🪙"
    XpSource.SHOWDOWN -> "👀"
    XpSource.HAND_STRENGTH -> "💪"
    XpSource.ACHIEVEMENT -> "🎯"
}

@Composable
private fun sourceColor(source: XpSource): ColorResource = when (source) {
    XpSource.BASE -> AppTheme.colors.content
    XpSource.INVESTMENT -> AppTheme.colors.accentSecondary
    XpSource.SHOWDOWN -> AppTheme.colors.accentTertiary
    XpSource.HAND_STRENGTH -> AppTheme.colors.info
    XpSource.ACHIEVEMENT -> AppTheme.colors.success
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
    val now = Clock.System.now().toEpochMilliseconds()
    val dayMs = 24L * 60L * 60L * 1000L
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
                    XpEvent(id = 1, deltaXp = 5, source = XpSource.ACHIEVEMENT, mode = XpMode.BOTS, handId = null, description = "First win", createdAtEpochMs = now),
                    XpEvent(id = 2, deltaXp = 3, source = XpSource.INVESTMENT, mode = XpMode.BOTS, handId = "42", createdAtEpochMs = now - 2L * 60L * 60L * 1000L),
                    XpEvent(id = 3, deltaXp = 5, source = XpSource.SHOWDOWN, mode = XpMode.BOTS, handId = "42", createdAtEpochMs = now - dayMs),
                    XpEvent(id = 4, deltaXp = 6, source = XpSource.HAND_STRENGTH, mode = XpMode.BOTS, handId = "41", createdAtEpochMs = now - 3L * dayMs),
                    XpEvent(id = 5, deltaXp = 2, source = XpSource.BASE, mode = XpMode.BOTS, handId = "40", createdAtEpochMs = now - 9L * dayMs),
                ),
            ),
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun StatsScreenPreview_WithXpBoost() {
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
                xpBoostExpiresAtEpochMs = 1_000_000L,
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
private fun StatsScreenPreview_AnonymousDisclosure() {
    PreviewContent {
        StatsScreen(
            state = StatsState(
                isLoading = false,
                isAnonymous = true,
                progression = Progression(
                    totalXp = 2_840,
                    handsPlayed = 412,
                    handsWon = 110,
                    handsFolded = 220,
                    handsLostAtShowdown = 82,
                    botHandsPlayed = 412,
                    updatedAtEpochMs = 0,
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

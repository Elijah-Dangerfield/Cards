package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dangerfield.cards.libraries.cards.Progression
import com.dangerfield.cards.libraries.cards.XpEvent
import com.dangerfield.cards.libraries.cards.XpMode
import com.dangerfield.cards.libraries.cards.XpSource
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun XpDetailSheetContent(
    state: XpDetailState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        XpHero(xp = state.progression.totalXp)
        Spacer(modifier = Modifier.height(24.dp))

        LifetimeStatsGrid(progression = state.progression)
        Spacer(modifier = Modifier.height(24.dp))

        if (state.recentEvents.isNotEmpty()) {
            SectionTitle("Recent XP")
            Spacer(modifier = Modifier.height(8.dp))
            RecentEventsList(events = state.recentEvents)
            Spacer(modifier = Modifier.height(24.dp))
        }

        SectionTitle("How you earn XP")
        Spacer(modifier = Modifier.height(8.dp))
        HowToEarn()
        Spacer(modifier = Modifier.height(24.dp))

        SectionTitle("What XP does for you")
        Spacer(modifier = Modifier.height(8.dp))
        WhatXpDoes()
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun XpHero(xp: Long) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
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
                typography = AppTheme.typography.Heading.H700,
                color = AppTheme.colors.text,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = formatThousands(xp),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "Lifetime XP",
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
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
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
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfaceSecondary.color),
    ) {
        events.forEachIndexed { index, event ->
            EventRow(event)
            if (index != events.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.04f)),
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
            event.handId?.let { handId ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Hand #$handId · ${modeLabel(event.mode)}",
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
private fun HowToEarn() {
    InfoCard {
        Bullet("Finishing a hand — every hand counts, even quick folds")
        Bullet("Chips you put in the pot — playing more invested hands earns more")
        Bullet("Reaching showdown — bonus for sticking around to the end")
        Bullet("Stronger hands at showdown — bigger reveals, bigger reward")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Bots count at half the rate of multiplayer. XP never depends on whether you win or lose the hand — just on how engaged you were.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun WhatXpDoes() {
    InfoCard {
        Text(
            text = "XP is your lifetime engagement score. It never goes down. Every session adds to it whether you stack chips or bust out.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Future updates will unlock cosmetics, table titles, and achievement badges as your XP climbs. Multiplayer earns 2× when it ships.",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AppTheme.colors.surfaceSecondary.color)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
private fun Bullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "·",
            typography = AppTheme.typography.Body.B500,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.text,
        )
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
private fun XpDetailSheetContent_Empty() {
    PreviewContent {
        XpDetailSheetContent(
            state = XpDetailState(isLoading = false),
            modifier = Modifier.padding(20.dp),
        )
    }
}

@Preview
@Composable
private fun XpDetailSheetContent_Populated() {
    PreviewContent {
        XpDetailSheetContent(
            state = XpDetailState(
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
                    XpEvent(1, 5, XpSource.BASE, XpMode.BOTS, "42", 0L),
                    XpEvent(2, 3, XpSource.INVESTMENT, XpMode.BOTS, "42", 0L),
                    XpEvent(3, 5, XpSource.SHOWDOWN, XpMode.BOTS, "42", 0L),
                    XpEvent(4, 6, XpSource.HAND_STRENGTH, XpMode.BOTS, "42", 0L),
                ),
            ),
            modifier = Modifier.padding(20.dp),
        )
    }
}

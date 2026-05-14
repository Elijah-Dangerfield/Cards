package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.ChipBadge
import com.dangerfield.cards.libraries.ui.components.FeatureCard
import com.dangerfield.cards.libraries.ui.components.FeatureCardAccents
import com.dangerfield.cards.libraries.ui.components.RankBadge
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.XpBadge
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToFeedback: () -> Unit,
    onNavigateToBugReport: () -> Unit,
    onPlayBots: (difficulty: String) -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onTapCash: () -> Unit,
    onStartGame: () -> Unit,
    onJoinGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    // Rank stays at 0 ("Unranked") for anon users — real Elo lands when they
    // claim their account and play multiplayer (see docs/decisions.md 2026-05-14
    // and the RankDetailSheet explainer). XP and chips are live via repos.
    HomeScreenContent(
        rank = if (state.isAnonymous) 0 else 1200,
        chips = state.chips,
        xp = state.xp,
        onPlayBots = onPlayBots,
        onTapRank = onTapRank,
        onTapXp = onTapXp,
        onTapCash = onTapCash,
        onStartGame = onStartGame,
        onJoinGame = onJoinGame,
        modifier = modifier,
    )
}

@Composable
private fun HomeScreenContent(
    rank: Int,
    chips: Long,
    xp: Long,
    onPlayBots: (difficulty: String) -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onTapCash: () -> Unit,
    onStartGame: () -> Unit,
    onJoinGame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Screen(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            // Rank · XP · Chips header — the three independent progression axes
            // (see docs/decisions.md 2026-05-14). Never collapse them into one.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RankBadge(rank = rank, onClick = onTapRank)
                XpBadge(xp = xp, onClick = onTapXp)
                Spacer(modifier = Modifier.weight(1f))
                ChipBadge(amount = chips, onClick = onTapCash)
            }

            Spacer(modifier = Modifier.height(28.dp))
            HeroChips(amount = chips)
            Spacer(modifier = Modifier.height(28.dp))

            SectionLabel("Play with friends")
            Spacer(modifier = Modifier.height(12.dp))
            FeatureCard(
                title = "Start a game",
                subtitle = "Get a code, invite your friends",
                accent = FeatureCardAccents.Gold,
                glyph = "✦",
                onClick = onStartGame,
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeatureCard(
                title = "Join with code",
                subtitle = "Enter a 6-letter room code",
                accent = FeatureCardAccents.Blue,
                glyph = "#",
                onClick = onJoinGame,
            )

            Spacer(modifier = Modifier.height(28.dp))
            SectionLabel("Practice against bots")
            Spacer(modifier = Modifier.height(12.dp))
            FeatureCard(
                title = "Casual table",
                subtitle = "Forgiving bots · learning mode",
                accent = FeatureCardAccents.Green,
                glyph = "♠",
                onClick = { onPlayBots("Casual") },
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeatureCard(
                title = "Standard table",
                subtitle = "Balanced bots · most realistic",
                accent = FeatureCardAccents.Blue,
                glyph = "♣",
                onClick = { onPlayBots("Standard") },
            )
            Spacer(modifier = Modifier.height(12.dp))
            FeatureCard(
                title = "Challenging table",
                subtitle = "Aggressive bots · they read you",
                accent = FeatureCardAccents.Magenta,
                glyph = "♦",
                onClick = { onPlayBots("Challenging") },
            )
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
private fun HeroChips(amount: Long) {
    Column {
        Text(
            text = "Your chips",
            typography = AppTheme.typography.Body.B400,
            color = AppTheme.colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = formatThousands(amount),
            typography = AppTheme.typography.Heading.H800,
            color = AppTheme.colors.text,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Body.B500,
        color = AppTheme.colors.text,
    )
}

private fun formatThousands(value: Long): String {
    val s = value.toString()
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
private fun HomeScreenPreview_Default() {
    PreviewContent {
        HomeScreenContent(
            rank = 1200,
            chips = 10_000,
            xp = 2_840,
            onPlayBots = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onStartGame = {},
            onJoinGame = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_BrokeAndLowRank() {
    PreviewContent {
        HomeScreenContent(
            rank = 800,
            chips = 0,
            xp = 60,
            onPlayBots = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onStartGame = {},
            onJoinGame = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_Whale() {
    PreviewContent {
        HomeScreenContent(
            rank = 2400,
            chips = 1_234_567,
            xp = 982_000,
            onPlayBots = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onStartGame = {},
            onJoinGame = {},
        )
    }
}

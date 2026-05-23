package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.AnimatedNumberText
import com.dangerfield.cards.libraries.ui.components.BalancePillSlot
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.FeatureCard
import com.dangerfield.cards.libraries.ui.components.FeatureCardAccents
import com.dangerfield.cards.libraries.ui.components.RankBadge
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.XpBadge
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.libraries.ui.system.color.PokerPalette
import com.dangerfield.cards.libraries.ui.system.color.ColorResource
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD300
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD1000
import com.dangerfield.cards.system.VerticalSpacerD1100
import com.dangerfield.cards.system.VerticalSpacerD1200
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlayBots: (difficulty: String) -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onTapCash: () -> Unit,
    onStartGame: () -> Unit,
    onJoinGame: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    // Rank stays at 0 ("Unranked") for anon users — real Elo lands when they
    // claim their account and play multiplayer (see docs/decisions.md 2026-05-14
    // and the RankDetailSheet explainer). XP and chips are live via repos.
    HomeScreenContent(
        rank = if (state.isAnonymous) 0 else 1200,
        // Nullable on purpose: null = "local DB hasn't emitted yet"
        // (first launch or post-wipe). The chip badge + hero render a
        // placeholder ("—") in that state rather than flashing "0"
        // before the sync lands. Once Room emits the row (existing
        // session) or the sync writes a balance (fresh session), the
        // value flows in and the placeholder swaps to the real number.
        chips = state.chips,
        xp = state.xp,
        activeRooms = state.activeRooms,
        onPlayBots = onPlayBots,
        onTapRank = onTapRank,
        onTapXp = onTapXp,
        onTapCash = onTapCash,
        onStartGame = onStartGame,
        onJoinGame = onJoinGame,
        onRejoinRoom = onRejoinRoom,
        onForfeitRoom = { code -> viewModel.takeAction(HomeAction.Forfeit(code)) },
        modifier = modifier,
    )
}

@Composable
private fun HomeScreenContent(
    rank: Int,
    chips: Long?,
    xp: Long,
    activeRooms: List<ActiveRoomSummary>,
    onPlayBots: (difficulty: String) -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onTapCash: () -> Unit,
    onStartGame: () -> Unit,
    onJoinGame: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    onForfeitRoom: (code: String) -> Unit,
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
            VerticalSpacerD500()
            BalancePillSlot(
                chips = chips,
                onChipsClick = onTapCash,
            ) {
                RankBadge(rank = rank, onClick = onTapRank)
                XpBadge(xp = xp, onClick = onTapXp)
            }

            activeRooms.forEach { room ->
                VerticalSpacerD600()
                ActiveRoomBanner(
                    code = room.code,
                    onRejoin = { onRejoinRoom(room.code) },
                    onForfeit = { onForfeitRoom(room.code) },
                )
            }

            VerticalSpacerD1100()
            HeroChips(amount = chips)
            VerticalSpacerD1200()

            SectionLabel("Play with friends")
            VerticalSpacerD600()
            FeatureCard(
                title = "Start a game",
                subtitle = "Get a code, invite your friends",
                accent = FeatureCardAccents.Gold,
                glyph = "✦",
                onClick = onStartGame,
            )
            VerticalSpacerD600()
            FeatureCard(
                title = "Join with code",
                subtitle = "Enter a 6-letter room code",
                accent = FeatureCardAccents.Blue,
                glyph = "#",
                onClick = onJoinGame,
            )

            VerticalSpacerD1100()
            SectionLabel("Practice against bots")
            VerticalSpacerD600()
            FeatureCard(
                title = "Casual table",
                subtitle = "Forgiving bots · learning mode",
                accent = FeatureCardAccents.Green,
                glyph = "♠",
                onClick = { onPlayBots("Casual") },
            )
            VerticalSpacerD600()
            FeatureCard(
                title = "Standard table",
                subtitle = "Balanced bots · most realistic",
                accent = FeatureCardAccents.Blue,
                glyph = "♣",
                onClick = { onPlayBots("Standard") },
            )
            VerticalSpacerD600()
            FeatureCard(
                title = "Challenging table",
                subtitle = "Aggressive bots · they read you",
                accent = FeatureCardAccents.Magenta,
                glyph = "♦",
                onClick = { onPlayBots("Challenging") },
            )
            VerticalSpacerD1000()
            BottomBarSpacer()
        }
    }
}

@Composable
private fun HeroChips(amount: Long?) {
    val goldText = remember { ColorResource.FromColor(PokerPalette.ChipGold, "chip-gold") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "YOUR CHIPS",
            typography = AppTheme.typography.Label.L400,
            color = AppTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        VerticalSpacerD300()
        if (amount == null) {
            // Hero placeholder mirrors the badge — same Display.D1200
            // size so the layout doesn't jump when the real number
            // arrives. textSecondary signals "not yet hydrated"
            // versus the gold-on-text contrast of a real balance.
            Text(
                text = "—",
                typography = AppTheme.typography.Display.D1200,
                color = AppTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        } else {
            AnimatedNumberText(
                value = amount,
                typography = AppTheme.typography.Display.D1200,
                color = goldText,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Heading.H500,
        color = AppTheme.colors.text,
    )
}

@Preview
@Composable
private fun HomeScreenPreview_Default() {
    PreviewContent {
        HomeScreenContent(
            rank = 1200,
            chips = 10_000,
            xp = 2_840,
            activeRooms = emptyList(),
            onPlayBots = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onStartGame = {},
            onJoinGame = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
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
            activeRooms = emptyList(),
            onPlayBots = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onStartGame = {},
            onJoinGame = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
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
            activeRooms = emptyList(),
            onPlayBots = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onStartGame = {},
            onJoinGame = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_WithActiveRoom() {
    PreviewContent {
        HomeScreenContent(
            rank = 1200,
            chips = 10_000,
            xp = 2_840,
            activeRooms = listOf(ActiveRoomSummary(code = "ABC123")),
            onPlayBots = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onStartGame = {},
            onJoinGame = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
        )
    }
}

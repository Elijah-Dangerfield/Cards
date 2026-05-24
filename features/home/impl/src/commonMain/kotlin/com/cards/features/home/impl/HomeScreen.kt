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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.FeatureCardAccents
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD1100
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlayBots: () -> Unit,
    onQuickMatch: () -> Unit,
    onFriendGame: () -> Unit,
    onTournament: () -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onTapCash: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    onTapFeaturedDrop: () -> Unit,
    onTapFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    // Rank stays at 0 ("Unranked") for anon users — real Elo lands when they
    // claim their account and play multiplayer (see docs/decisions.md 2026-05-14
    // and the RankDetailSheet explainer). XP and chips are live via repos.
    HomeScreenContent(
        displayName = state.userName,
        avatarEmoji = state.avatarEmoji,
        avatarBackgroundColorHex = state.avatarBackgroundColorHex,
        rank = if (state.isAnonymous) 0 else 1200,
        // Nullable on purpose: null = "local DB hasn't emitted yet"
        // (first launch or post-wipe). The chip badge renders a
        // placeholder ("—") in that state rather than flashing "0"
        // before the sync lands.
        chips = state.chips,
        xp = state.xp,
        activeRooms = state.activeRooms,
        onPlayBots = onPlayBots,
        onQuickMatch = onQuickMatch,
        onFriendGame = onFriendGame,
        onTournament = onTournament,
        onTapRank = onTapRank,
        onTapXp = onTapXp,
        onTapCash = onTapCash,
        onRejoinRoom = onRejoinRoom,
        onForfeitRoom = { code -> viewModel.takeAction(HomeAction.Forfeit(code)) },
        onTapFeaturedDrop = onTapFeaturedDrop,
        onTapFriends = onTapFriends,
        modifier = modifier,
    )
}

@Composable
private fun HomeScreenContent(
    displayName: String?,
    avatarEmoji: String?,
    avatarBackgroundColorHex: String?,
    rank: Int,
    chips: Long?,
    xp: Long,
    activeRooms: List<ActiveRoomSummary>,
    onPlayBots: () -> Unit,
    onQuickMatch: () -> Unit,
    onFriendGame: () -> Unit,
    onTournament: () -> Unit,
    onTapRank: () -> Unit,
    onTapXp: () -> Unit,
    onTapCash: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    onForfeitRoom: (code: String) -> Unit,
    onTapFeaturedDrop: () -> Unit,
    onTapFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tickerSignals = remember { defaultTickerSignals() }
    val previewFriends = remember { previewFriends() }
    val featuredDrop = remember { previewFeaturedDrop() }
    Screen(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            VerticalSpacerD500()
            // Identity strip — avatar + name + lvl/rank line on the
            // left; chip stack pinned right. The big brand moment at
            // the top of the screen.
            IdentityStrip(
                displayName = displayName,
                avatarEmoji = avatarEmoji,
                avatarBackgroundColorHex = avatarBackgroundColorHex,
                xp = xp,
                rank = rank,
                chips = chips,
                onTapProfile = onTapXp,
                onTapChips = onTapCash,
            )

            VerticalSpacerD800()
            // Live activity ticker — the "living ecosystem" surface.
            // Real signals once they exist; structural truths for V1.
            ActivityTicker(signals = tickerSignals)

            // Active-room banner only when the user has one in flight.
            activeRooms.forEach { room ->
                VerticalSpacerD600()
                ActiveRoomBanner(
                    code = room.code,
                    onRejoin = { onRejoinRoom(room.code) },
                    onForfeit = { onForfeitRoom(room.code) },
                )
            }

            VerticalSpacerD1100()
            SectionLabel("Take a seat")
            VerticalSpacerD600()
            HomeCtaCard(
                title = "Practice",
                subtitle = "Bots, your pace. No chips on the line.",
                glyph = "♠",
                accent = FeatureCardAccents.Green,
                onClick = onPlayBots,
            )
            VerticalSpacerD600()
            HomeCtaCard(
                title = "Quick Match",
                subtitle = "Drop into a public seat.",
                glyph = "⚯",
                accent = FeatureCardAccents.Blue,
                onClick = onQuickMatch,
                trailing = "Soon",
            )
            VerticalSpacerD600()
            HomeCtaCard(
                title = "Friend Game",
                subtitle = "Room code · just you and yours.",
                glyph = "✦",
                accent = FeatureCardAccents.Gold,
                onClick = onFriendGame,
            )
            VerticalSpacerD600()
            HomeCtaCard(
                title = "Tournament",
                subtitle = "Royal Flush Tournament. Quarterly.",
                glyph = "♛",
                accent = FeatureCardAccents.Magenta,
                onClick = onTournament,
                enabled = false,
                trailing = "V2",
            )

            VerticalSpacerD1100()
            FriendsStrip(friends = previewFriends, onSeeAll = onTapFriends)

            VerticalSpacerD1100()
            FeaturedCosmeticCard(item = featuredDrop, onClick = onTapFeaturedDrop)

            VerticalSpacerD1100()
            BottomBarSpacer()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        typography = AppTheme.typography.Heading.H500,
        color = AppTheme.colors.text,
        modifier = Modifier.fillMaxWidth(),
    )
}

// --------------------------------------------------------------------------
// Fake data — V1 placeholders for signals that don't have real sources yet.
//
// Activity ticker: spec-honest "structural truths" (season countdowns,
// table readiness) rather than invented player names. Friends + featured-
// drop: shaped to drop in once the real sources land.
// --------------------------------------------------------------------------

private fun defaultTickerSignals(): List<String> = listOf(
    "Bots warm and waiting.",
    "Season 1 of the Hall — 5 weeks left.",
    "Slate Felt drops this week.",
    "Royal Flush Tournament arrives in V2.",
)

private fun previewFriends(): List<FriendOnline> = listOf(
    FriendOnline(
        displayName = "Vivienne",
        emoji = "🦊",
        avatarBackgroundColorHex = "#E48A58",
        tableLabel = "50/100 standard table",
    ),
    FriendOnline(
        displayName = "Steve",
        emoji = "🐢",
        avatarBackgroundColorHex = "#5894E4",
        tableLabel = "Heads-up vs Mike",
    ),
)

private fun previewFeaturedDrop(): FeaturedCosmetic = FeaturedCosmetic(
    name = "Slate Felt",
    tagline = "Limited drop · 5 days left",
    swatchColor = Color(0xFF3A4750),
)

// --------------------------------------------------------------------------
// Previews — pin the layout across the states Home actually renders:
// fresh anon, hydrated long-term player, broke + low rank, active room
// reattachment, and the hydrating-from-cold pre-balance state.
// --------------------------------------------------------------------------

@Preview
@Composable
private fun HomeScreenPreview_FreshAnonymous() {
    PreviewContent {
        HomeScreenContent(
            displayName = "Elijah",
            avatarEmoji = "🦊",
            avatarBackgroundColorHex = "#E48A58",
            rank = 0,
            chips = 10_000,
            xp = 240,
            activeRooms = emptyList(),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_RankedPlayer() {
    PreviewContent {
        HomeScreenContent(
            displayName = "Vivienne",
            avatarEmoji = "🦊",
            avatarBackgroundColorHex = "#A8E458",
            rank = 1320,
            chips = 124_500,
            xp = 12_840,
            activeRooms = emptyList(),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_WithActiveRoom() {
    PreviewContent {
        HomeScreenContent(
            displayName = "Elijah",
            avatarEmoji = "♠",
            avatarBackgroundColorHex = "#5894E4",
            rank = 0,
            chips = 10_000,
            xp = 2_840,
            activeRooms = listOf(ActiveRoomSummary(code = "ABC123")),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_HydratingFromCold() {
    PreviewContent {
        HomeScreenContent(
            displayName = null,
            avatarEmoji = null,
            avatarBackgroundColorHex = null,
            rank = 0,
            chips = null,
            xp = 0,
            activeRooms = emptyList(),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapRank = {},
            onTapXp = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
        )
    }
}

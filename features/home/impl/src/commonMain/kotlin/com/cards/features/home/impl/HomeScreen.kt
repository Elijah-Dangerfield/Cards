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
    onTapAvatar: () -> Unit,
    onTapCash: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    onTapFeaturedDrop: () -> Unit,
    onTapFriends: () -> Unit,
    onTapAchievements: () -> Unit,
    onAddRecentOpponent: (opponentId: String) -> Unit,
    onSeeAllRecentOpponents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    HomeScreenContent(
        displayName = state.userName,
        avatarEmoji = state.avatarEmoji,
        avatarBackgroundColorHex = state.avatarBackgroundColorHex,
        // Nullable on purpose: null = "local DB hasn't emitted yet"
        // (first launch or post-wipe). The chip pill renders a
        // placeholder ("—") in that state rather than flashing "0"
        // before the sync lands.
        chips = state.chips,
        activeRooms = state.activeRooms,
        onPlayBots = onPlayBots,
        onQuickMatch = onQuickMatch,
        onFriendGame = onFriendGame,
        onTournament = onTournament,
        onTapAvatar = onTapAvatar,
        onTapCash = onTapCash,
        onRejoinRoom = onRejoinRoom,
        onForfeitRoom = { code -> viewModel.takeAction(HomeAction.Forfeit(code)) },
        onTapFeaturedDrop = onTapFeaturedDrop,
        onTapFriends = onTapFriends,
        onTapAchievements = onTapAchievements,
        onAddRecentOpponent = onAddRecentOpponent,
        onSeeAllRecentOpponents = onSeeAllRecentOpponents,
        modifier = modifier,
    )
}

@Composable
private fun HomeScreenContent(
    displayName: String?,
    avatarEmoji: String?,
    avatarBackgroundColorHex: String?,
    chips: Long?,
    activeRooms: List<ActiveRoomSummary>,
    onPlayBots: () -> Unit,
    onQuickMatch: () -> Unit,
    onFriendGame: () -> Unit,
    onTournament: () -> Unit,
    onTapAvatar: () -> Unit,
    onTapCash: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    onForfeitRoom: (code: String) -> Unit,
    onTapFeaturedDrop: () -> Unit,
    onTapFriends: () -> Unit,
    onTapAchievements: () -> Unit,
    onAddRecentOpponent: (opponentId: String) -> Unit,
    onSeeAllRecentOpponents: () -> Unit,
    modifier: Modifier = Modifier,
    // ----- Fake-data injection points (V1) --------------------------------
    // The fake-data surfaces (ticker / friends / recents / achievements
    // / featured drop) take their content as parameters so previews can
    // exercise every state. Production callers use the defaults below
    // until real reactive sources land — see docs/todo.md.
    tickerSignals: List<String> = remember { defaultTickerSignals() },
    onlineFriends: List<FriendOnline> = remember { defaultOnlineFriends() },
    pendingFriendRequests: Int = 2,
    recentAchievements: List<RecentAchievement> = remember { defaultRecentAchievements() },
    recentOpponents: List<RecentOpponent> = remember { defaultRecentOpponents() },
    featuredDrop: FeaturedCosmetic = remember { defaultFeaturedDrop() },
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
            // Slim "you are here" header. The profile screen owns the
            // big avatar / level / rank treatment — Home just exposes
            // tap-back-to-profile + tap-into-shop affordances.
            HomeHeader(
                avatarName = displayName,
                avatarEmoji = avatarEmoji,
                avatarBackgroundColorHex = avatarBackgroundColorHex,
                chips = chips,
                onTapAvatar = onTapAvatar,
                onTapChips = onTapCash,
            )

            VerticalSpacerD800()
            // Living-ecosystem ticker. Real signals once they exist;
            // structural truths for V1.
            ActivityTicker(signals = tickerSignals)

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
            RecentAchievementsStrip(
                items = recentAchievements,
                onSeeAll = onTapAchievements,
            )

            VerticalSpacerD1100()
            FriendsStrip(
                friends = onlineFriends,
                pendingRequests = pendingFriendRequests,
                onSeeAll = onTapFriends,
            )

            VerticalSpacerD1100()
            RecentlyPlayedWithStrip(
                opponents = recentOpponents,
                onAddFriend = { opponent -> onAddRecentOpponent(opponent.id) },
                onSeeAll = onSeeAllRecentOpponents,
            )

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
// table readiness) rather than invented player names. Friends + recents +
// achievements + featured drop: shaped to drop in once the real sources
// land. See docs/todo.md (§Home redesign — wire-up to real data).
// --------------------------------------------------------------------------

private fun defaultTickerSignals(): List<String> = listOf(
    "Bots warm and waiting.",
    "Season 1 of the Hall — 5 weeks left.",
    "Slate Felt drops this week.",
    "Royal Flush Tournament arrives in V2.",
)

private fun defaultOnlineFriends(): List<FriendOnline> = listOf(
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

private fun defaultRecentAchievements(): List<RecentAchievement> = listOf(
    RecentAchievement(
        id = "first_hand",
        name = "First Blood",
        icon = "🩸",
        rarityColor = Color(0xFF8E7CC3),
    ),
    RecentAchievement(
        id = "hands_100",
        name = "Centurion",
        icon = "💯",
        rarityColor = Color(0xFF4FC3F7),
    ),
    RecentAchievement(
        id = "pot_2k",
        name = "Big Pot",
        icon = "💰",
        rarityColor = Color(0xFFE07AB1),
    ),
)

private fun defaultRecentOpponents(): List<RecentOpponent> = listOf(
    RecentOpponent(
        id = "u_pat",
        displayName = "Patrice",
        emoji = "🦁",
        avatarBackgroundColorHex = "#C658E4",
    ),
    RecentOpponent(
        id = "u_jules",
        displayName = "Jules",
        emoji = "🐙",
        avatarBackgroundColorHex = "#58C0E4",
        requestSent = true,
    ),
    RecentOpponent(
        id = "u_omar",
        displayName = "Omar",
        emoji = "🦅",
        avatarBackgroundColorHex = "#A8E458",
    ),
)

private fun defaultFeaturedDrop(): FeaturedCosmetic = FeaturedCosmetic(
    name = "Slate Felt",
    tagline = "Limited drop · 5 days left",
    swatchColor = Color(0xFF3A4750),
)

// --------------------------------------------------------------------------
// Previews — pin the layout across the states Home actually renders:
// fresh user / cold-boot hydrating / active room reattach / empty social
// state (no friends online, no requests, no recents). Together they
// exercise every conditional in [HomeScreenContent].
// --------------------------------------------------------------------------

@Preview
@Composable
private fun HomeScreenPreview_FullyHydrated() {
    PreviewContent {
        HomeScreenContent(
            displayName = "Elijah",
            avatarEmoji = "🦊",
            avatarBackgroundColorHex = "#E48A58",
            chips = 12_300,
            activeRooms = emptyList(),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapAvatar = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
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
            chips = 10_000,
            activeRooms = listOf(ActiveRoomSummary(code = "ABC123")),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapAvatar = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
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
            chips = null,
            activeRooms = emptyList(),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapAvatar = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_NoSocialState() {
    // Fresh user — no friends online, no pending requests, no
    // recent opponents, no achievements unlocked. Every shelf that
    // doesn't survive an empty list disappears; the layout collapses
    // around the CTAs + featured drop.
    PreviewContent {
        HomeScreenContent(
            displayName = "Newbie",
            avatarEmoji = "👋",
            avatarBackgroundColorHex = "#A8E458",
            chips = 1_000,
            activeRooms = emptyList(),
            onPlayBots = {},
            onQuickMatch = {},
            onFriendGame = {},
            onTournament = {},
            onTapAvatar = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFeaturedDrop = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
            onlineFriends = emptyList(),
            pendingFriendRequests = 0,
            recentAchievements = emptyList(),
            recentOpponents = emptyList(),
        )
    }
}

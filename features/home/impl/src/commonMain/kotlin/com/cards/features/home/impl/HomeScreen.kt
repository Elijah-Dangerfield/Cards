package com.dangerfield.cards.features.home.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_cta_practice_subtitle_short
import cards.libraries.resources.generated.resources.home_cta_practice_title
import cards.libraries.resources.generated.resources.home_cta_private_room_title
import cards.libraries.resources.generated.resources.home_cta_public_rooms_title
import cards.libraries.resources.generated.resources.home_cta_tournament_subtitle
import cards.libraries.resources.generated.resources.home_cta_tournament_title
import cards.libraries.resources.generated.resources.home_play_private_room_subtitle
import cards.libraries.resources.generated.resources.home_play_public_rooms_button
import cards.libraries.resources.generated.resources.home_play_public_rooms_subtitle
import cards.libraries.resources.generated.resources.home_section_play
import cards.libraries.resources.generated.resources.home_tag_soon
import com.dangerfield.cards.libraries.cards.LevelProgress
import com.dangerfield.cards.libraries.cards.levelProgressFor
import com.dangerfield.cards.libraries.ui.PreviewBottomBar
import com.dangerfield.cards.libraries.ui.PreviewContent
import com.dangerfield.cards.libraries.ui.components.BottomBarSpacer
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.header.SectionHeader
import com.dangerfield.cards.libraries.ui.system.color.FeatureCardAccents
import com.dangerfield.cards.libraries.ui.screenContentPadding
import com.dangerfield.cards.system.Dimension
import com.dangerfield.cards.system.VerticalSpacerD500
import com.dangerfield.cards.system.VerticalSpacerD600
import com.dangerfield.cards.system.VerticalSpacerD800
import com.dangerfield.cards.system.VerticalSpacerD1100
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onPlayBots: () -> Unit,
    onPublicRooms: () -> Unit,
    onPrivateRoom: () -> Unit,
    onTournament: () -> Unit,
    onStartTutorial: () -> Unit,
    onTapLevel: () -> Unit,
    onTapCash: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    onTapFriends: () -> Unit,
    onTapAchievements: () -> Unit,
    onAddRecentOpponent: (opponentId: String) -> Unit,
    onSeeAllRecentOpponents: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    // On every return to Home, replay any chip change that landed while the user
    // was away (won/lost chips on another screen) so the odometer always animates
    // it — checked against the local source of truth, no backend hit.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.takeAction(HomeAction.ScreenResumed) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.takeAction(HomeAction.ScreenPaused) }
    // [recent-achievements-delay] One line per recomposition of HomeScreen
    // — pin whether the VM state actually carries achievements at the
    // moment Compose pulls it. If `vm=<hash>` stays constant across tab
    // switches and `items` is non-empty immediately on return, the delay
    // is *somewhere downstream of this read* (animation, layout, etc).
    // If `items=0` for the first few frames, the VM state was reset.
    com.dangerfield.cards.libraries.core.logging.KLog
        .withTag("HomeScreen")
        .i {
            "[recent-achievements-delay] compose — vm=${viewModel.hashCode()} " +
                "items=${state.recentAchievements.size}"
        }
    // The level-up celebration is a routed full-screen destination
    // ([LevelUpRoute]); the Home entry point navigates to it off
    // `state.levelUpCelebration`. Home itself just renders its content.
    HomeScreenContent(
        levelProgress = state.levelProgress,
        displayName = state.userName ?: "You",
        avatarEmoji = state.avatarEmoji,
        avatarBackgroundColorHex = state.avatarBackgroundColorHex,
        // Nullable on purpose: null = "local DB hasn't emitted yet"
        // (first launch or post-wipe). The chip pill renders a
        // placeholder ("—") in that state rather than flashing "0"
        // before the sync lands.
        chips = state.chips,
        chipsRevealFrom = state.chipsRevealFrom,
        chipsRevealKey = state.chipsRevealKey,
        activeRooms = state.activeRooms,
        showTutorialBanner = !state.tutorialBannerDismissed,
        onStartTutorial = onStartTutorial,
        onDismissTutorialBanner = { viewModel.takeAction(HomeAction.DismissTutorialBanner) },
        onPlayBots = onPlayBots,
        onPublicRooms = onPublicRooms,
        onPrivateRoom = onPrivateRoom,
        onTournament = onTournament,
        onTapLevel = onTapLevel,
        onTapCash = onTapCash,
        onRejoinRoom = onRejoinRoom,
        onForfeitRoom = { code -> viewModel.takeAction(HomeAction.Forfeit(code)) },
        onTapFriends = onTapFriends,
        onTapAchievements = onTapAchievements,
        onAddRecentOpponent = onAddRecentOpponent,
        onSeeAllRecentOpponents = onSeeAllRecentOpponents,
        recentAchievements = state.recentAchievements,
        recentOpponents = state.recentOpponents,
        pendingFriendRequests = state.pendingFriendRequests,
        socialEnabled = state.socialEnabled,
        modifier = modifier,
        scrollState = scrollState,
    )
}

@Composable
private fun HomeScreenContent(
    levelProgress: LevelProgress,
    chips: Long?,
    chipsRevealFrom: Long? = null,
    chipsRevealKey: Int = 0,
    // Identity defaults keep the many previews terse; production always
    // passes the real values from HomeState.
    displayName: String = "QuietAce72",
    avatarEmoji: String? = "🦊",
    avatarBackgroundColorHex: String? = "#E48A58",
    activeRooms: List<ActiveRoomSummary>,
    showTutorialBanner: Boolean = false,
    onStartTutorial: () -> Unit = {},
    onDismissTutorialBanner: () -> Unit = {},
    onPlayBots: () -> Unit,
    onPublicRooms: () -> Unit,
    onPrivateRoom: () -> Unit,
    onTournament: () -> Unit,
    onTapLevel: () -> Unit,
    onTapCash: () -> Unit,
    onRejoinRoom: (code: String) -> Unit,
    onForfeitRoom: (code: String) -> Unit,
    onTapFriends: () -> Unit,
    onTapAchievements: () -> Unit,
    onAddRecentOpponent: (opponentId: String) -> Unit,
    onSeeAllRecentOpponents: () -> Unit,
    // Master gate for the social shelves (SOC-2). Default true so previews
    // exercise the populated layout; production passes `state.socialEnabled`,
    // which defaults off until the `social.enabled` app-config flag flips on.
    socialEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    // ----- Shelf content --------------------------------------------------
    // Each shelf takes its content as a parameter so previews can exercise
    // every state. `recentAchievements` and `recentOpponents` are real-data
    // driven from `HomeViewModel`; the friends shelf is still a fake source
    // until the presence/graph wiring lands — see docs/todo.md. Defaults
    // below only feed previews that don't pass a list.
    onlineFriends: List<FriendOnline> = remember { defaultOnlineFriends() },
    pendingFriendRequests: Int = 0,
    recentAchievements: List<RecentAchievement> = emptyList(),
    recentOpponents: List<RecentOpponent> = remember { defaultRecentOpponents() },
) {
    Screen(modifier = modifier) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .screenContentPadding(paddingValues = paddingValues),
        ) {
            VerticalSpacerD500()
            // Slim header — ringed avatar + name + level (left) + chip
            // balance (right). The avatar's ring encodes XP progress; the
            // chip pill matches the one the shop uses.
            HomeHeader(
                levelProgress = levelProgress,
                displayName = displayName,
                avatarEmoji = avatarEmoji,
                avatarBackgroundColorHex = avatarBackgroundColorHex,
                chips = chips,
                chipsRevealFrom = chipsRevealFrom,
                chipsRevealKey = chipsRevealKey,
                onTapLevel = onTapLevel,
                onTapChips = onTapCash,
            )

            AnimatedVisibility(
                visible = showTutorialBanner,
                // Enter snaps — every revisit to home would otherwise
                // replay the expand+fade and read as a fresh "new here"
                // appearance. Only the dismissal animates.
                enter = EnterTransition.None,
                exit = shrinkVertically(animationSpec = tween(220)) +
                    fadeOut(animationSpec = tween(140)),
            ) {
                Column {
                    VerticalSpacerD800()
                    TutorialBanner(
                        onStart = onStartTutorial,
                        onDismiss = onDismissTutorialBanner,
                    )
                }
            }

            activeRooms.forEach { room ->
                VerticalSpacerD600()
                ActiveRoomBanner(
                    code = room.code,
                    onRejoin = { onRejoinRoom(room.code) },
                    onForfeit = { onForfeitRoom(room.code) },
                )
            }

            VerticalSpacerD800()
            SectionHeader(title = stringResource(Res.string.home_section_play))
            VerticalSpacerD600()
            // Rooms-forward home (SPEC §5): Public rooms is the hero (pick a
            // buy-in, auto-seated), Private room opens the create/join sheet,
            // and Practice / Tournament sit below as the two smaller tiles.
            PublicRoomsCard(
                title = stringResource(Res.string.home_cta_public_rooms_title),
                subtitle = stringResource(Res.string.home_play_public_rooms_subtitle),
                cta = stringResource(Res.string.home_play_public_rooms_button),
                onClick = onPublicRooms,
            )
            VerticalSpacerD500()
            PrivateRoomCard(
                title = stringResource(Res.string.home_cta_private_room_title),
                subtitle = stringResource(Res.string.home_play_private_room_subtitle),
                onClick = onPrivateRoom,
            )
            VerticalSpacerD500()
            Row(horizontalArrangement = Arrangement.spacedBy(Dimension.D500)) {
                PlayTileCard(
                    title = stringResource(Res.string.home_cta_practice_title),
                    subtitle = stringResource(Res.string.home_cta_practice_subtitle_short),
                    glyph = "♠",
                    accent = FeatureCardAccents.Green,
                    onClick = onPlayBots,
                    modifier = Modifier.weight(1f),
                )
                PlayTileCard(
                    title = stringResource(Res.string.home_cta_tournament_title),
                    subtitle = stringResource(Res.string.home_cta_tournament_subtitle),
                    glyph = "♛",
                    accent = FeatureCardAccents.Magenta,
                    onClick = onTournament,
                    modifier = Modifier.weight(1f),
                    tag = stringResource(Res.string.home_tag_soon),
                )
            }

            if (socialEnabled) {
                VerticalSpacerD1100()
                FriendsStrip(
                    friends = onlineFriends,
                    pendingRequests = pendingFriendRequests,
                    onSeeAll = onTapFriends,
                )
            }

            VerticalSpacerD1100()
            RecentAchievementsStrip(
                items = recentAchievements,
                onSeeAll = onTapAchievements,
            )

            if (socialEnabled) {
                VerticalSpacerD1100()
                RecentlyPlayedWithStrip(
                    opponents = recentOpponents,
                    onAddFriend = { opponent -> onAddRecentOpponent(opponent.id) },
                    onSeeAll = onSeeAllRecentOpponents,
                )
            }

            VerticalSpacerD1100()
            BottomBarSpacer()
        }
    }
}

// --------------------------------------------------------------------------
// Fake data — V1 placeholders for signals that don't have real sources yet.
//
// Friends + recents: shaped to drop in once the real sources land. See
// docs/todo.md (§Home redesign — wire-up to real data).
// --------------------------------------------------------------------------

private fun defaultOnlineFriends(): List<FriendOnline> = listOf(
    FriendOnline(
        id = "f_jordan",
        displayName = "Jordan",
        emoji = "🦊",
        avatarBackgroundColorHex = "#E48A58",
        tableLabel = "Friend room",
    ),
    FriendOnline(
        id = "f_priya",
        displayName = "Priya",
        emoji = "💀",
        avatarBackgroundColorHex = "#9E9E9E",
        tableLabel = "Practice",
    ),
    FriendOnline(
        id = "f_marcus",
        displayName = "Marcus",
        emoji = "🐉",
        avatarBackgroundColorHex = "#5DA75A",
        tableLabel = "Quick match",
    ),
)

private fun defaultRecentAchievements(): List<RecentAchievement> = emptyList()

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

// --------------------------------------------------------------------------
// Previews — pin the layout across the states Home actually renders:
// fresh user / cold-boot hydrating / active room reattach / empty social
// state (no friends online, no requests, no recents). Together they
// exercise every conditional in [HomeScreenContent].
// --------------------------------------------------------------------------

@Preview(heightDp = 1200)
@Composable
private fun HomeScreenPreview_FullyHydrated() {
    PreviewContent(bottomBar = PreviewBottomBar.Home) {
        HomeScreenContent(
            levelProgress = levelProgressFor(totalXp = 1_140),
            chips = 12_300,
            activeRooms = emptyList(),
            showTutorialBanner = true,
            onPlayBots = {},
            onPublicRooms = {},
            onPrivateRoom = {},
            onTournament = {},
            onTapLevel = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
            recentAchievements = previewRecentAchievements(),
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_WithActiveRoom() {
    PreviewContent(bottomBar = PreviewBottomBar.Home) {
        HomeScreenContent(
            levelProgress = levelProgressFor(totalXp = 10_000),
            chips = 10_000,
            activeRooms = listOf(ActiveRoomSummary(code = "ABC123")),
            onPlayBots = {},
            onPublicRooms = {},
            onPrivateRoom = {},
            onTournament = {},
            onTapLevel = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
            recentAchievements = previewRecentAchievements(),
        )
    }
}

@Preview
@Composable
private fun HomeScreenPreview_HydratingFromCold() {
    PreviewContent(bottomBar = PreviewBottomBar.Home) {
        HomeScreenContent(
            levelProgress = LevelProgress(level = 1, totalXp = 0, xpAtLevelStart = 0, xpForNextLevel = 100),
            chips = null,
            activeRooms = emptyList(),
            onPlayBots = {},
            onPublicRooms = {},
            onPrivateRoom = {},
            onTournament = {},
            onTapLevel = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
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
    // around the CTAs.
    PreviewContent(bottomBar = PreviewBottomBar.Home) {
        HomeScreenContent(
            levelProgress = LevelProgress(level = 1, totalXp = 10, xpAtLevelStart = 0, xpForNextLevel = 100),
            chips = 1_000,
            activeRooms = emptyList(),
            onPlayBots = {},
            onPublicRooms = {},
            onPrivateRoom = {},
            onTournament = {},
            onTapLevel = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
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

@Preview
@Composable
private fun HomeScreenPreview_SocialDisabled() {
    // SOC-2 default: social.enabled is off, so the Friends and
    // recently-played-with shelves don't render at all — the recent-unlocks
    // shelf sits directly under the play CTAs. Achievements stay visible.
    PreviewContent(bottomBar = PreviewBottomBar.Home) {
        HomeScreenContent(
            levelProgress = levelProgressFor(totalXp = 1_140),
            chips = 12_300,
            activeRooms = emptyList(),
            onPlayBots = {},
            onPublicRooms = {},
            onPrivateRoom = {},
            onTournament = {},
            onTapLevel = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
            socialEnabled = false,
        )
    }
}

@Preview(widthDp = 800, heightDp = 380)
@Composable
private fun HomeScreenPreview_Landscape() {
    // Landscape lens on the hydrated home: a phone held sideways shows the
    // top band (level / chips / CTAs) above the fold. Surfaces whether the
    // single column stretches uncomfortably wide before any layout tuning.
    PreviewContent(bottomBar = PreviewBottomBar.Home) {
        HomeScreenContent(
            levelProgress = levelProgressFor(totalXp = 1_140),
            chips = 12_300,
            activeRooms = emptyList(),
            showTutorialBanner = true,
            onPlayBots = {},
            onPublicRooms = {},
            onPrivateRoom = {},
            onTournament = {},
            onTapLevel = {},
            onTapCash = {},
            onRejoinRoom = {},
            onForfeitRoom = {},
            onTapFriends = {},
            onTapAchievements = {},
            onAddRecentOpponent = {},
            onSeeAllRecentOpponents = {},
            recentAchievements = previewRecentAchievements(),
        )
    }
}

/**
 * Build a preview set by picking a few real achievements from the
 * registry — keeps the medallion's rarity / icon / criterion math
 * honest in previews instead of inventing UI data the renderer would
 * never see at runtime.
 */
private fun previewRecentAchievements(): List<RecentAchievement> {
    val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
    val day = 24L * 60L * 60L * 1000L
    return com.dangerfield.cards.libraries.cards.AllAchievements
        .filter { !it.isMystery }
        .take(4)
        .mapIndexed { index, achievement ->
            RecentAchievement(
                achievement = achievement,
                earnedAtEpochMs = now - (index + 1) * day,
            )
        }
}

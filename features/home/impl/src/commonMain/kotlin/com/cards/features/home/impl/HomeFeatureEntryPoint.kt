package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_coming_soon_friend_requests_body
import cards.libraries.resources.generated.resources.home_coming_soon_friend_requests_title
import cards.libraries.resources.generated.resources.home_coming_soon_friends_body
import cards.libraries.resources.generated.resources.home_coming_soon_friends_title
import cards.libraries.resources.generated.resources.home_coming_soon_quick_match_body
import cards.libraries.resources.generated.resources.home_coming_soon_quick_match_title
import cards.libraries.resources.generated.resources.home_coming_soon_recently_played_body
import cards.libraries.resources.generated.resources.home_coming_soon_recently_played_title
import org.jetbrains.compose.resources.stringResource
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.home.WelcomeDialogRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.progression.AchievementsRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.features.room.TutorialRoute
import com.dangerfield.cards.features.shop.ShopGraph
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.ObserveWithLifecycle
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.OnTabReselected
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.dialog
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.navigation.toRouteOrNull
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class HomeFeatureEntryPoint(
    private val homeViewModelFactory: () -> HomeViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<HomeRoute> {
            val viewModel: HomeViewModel = viewModel { homeViewModelFactory() }
            val scrollState = rememberScrollState()
            val scope = rememberCoroutineScope()
            router.OnTabReselected(HomeRoute()) {
                scope.launch { scrollState.animateScrollTo(0) }
            }
            LaunchedEffect(Unit) {
                KLog.withTag("HomeFeatureEntryPoint").d { "Home route entered" }
            }
            ObserveWithLifecycle(viewModel.eventFlow) { event ->
                when (event) {
                    is HomeEvent.OpenWelcomeDialog -> {
                        val payload = event.payload
                        router.navigate(
                            WelcomeDialogRoute(
                                displayName = payload.displayName,
                                avatarEmoji = payload.avatarEmoji,
                                avatarBackgroundColorHex = payload.avatarBackgroundColorHex,
                                chips = payload.chips,
                            )
                        )
                    }
                }
            }
            // The bot-table setup dialog now picks difficulty *and*
            // seat count in one place, so Home's single Practice CTA
            // opens it directly — no intermediate difficulty picker.
            var botSetupOpen by remember { mutableStateOf(false) }
            // "Coming soon" sheet state, parameterized so the same
            // surface serves Quick Match (not built) and Tournament
            // (V2). Null = closed.
            var comingSoon by remember { mutableStateOf<ComingSoonContent?>(null) }

            val quickMatchTitle = stringResource(Res.string.home_coming_soon_quick_match_title)
            val quickMatchBody = stringResource(Res.string.home_coming_soon_quick_match_body)
            val friendsTitle = stringResource(Res.string.home_coming_soon_friends_title)
            val friendsBody = stringResource(Res.string.home_coming_soon_friends_body)
            val friendRequestsTitle = stringResource(Res.string.home_coming_soon_friend_requests_title)
            val friendRequestsBody = stringResource(Res.string.home_coming_soon_friend_requests_body)
            val recentlyPlayedTitle = stringResource(Res.string.home_coming_soon_recently_played_title)
            val recentlyPlayedBody = stringResource(Res.string.home_coming_soon_recently_played_body)

            HomeScreen(
                viewModel = viewModel,
                onPlayBots = { botSetupOpen = true },
                onQuickMatch = {
                    // Spec §5.3 — Quick Match needs the public-rooms
                    // matchmaker. Until that lands, surface an honest
                    // "coming soon" sheet rather than a stub navigation.
                    comingSoon = ComingSoonContent(
                        title = quickMatchTitle,
                        emoji = "⚯",
                        body = quickMatchBody,
                    )
                },
                // Friend Game = the existing lobby flow (create/join via
                // room code). Spec §5.2 calls this the "Friend Game"
                // entry point; the lobby screen is the actual surface.
                onFriendGame = { router.navigate(LobbyRoute()) },
                onStartTutorial = { router.navigate(TutorialRoute()) },
                // Level pill → Stats — the screen-of-record for the
                // full level / XP breakdown.
                onTapLevel = { router.navigate(StatsRoute()) },
                onTapCash = { router.switchTab(ShopGraph) },
                onRejoinRoom = { code -> router.navigate(LobbyRoute(prefilledCode = code)) },
                onTapAchievements = { router.navigate(AchievementsRoute()) },
                // No standalone Friends surface yet — friend graph,
                // online presence, and the requests inbox all ship
                // together in V1.x. Until then a "coming soon" sheet
                // explains what's planned.
                onTapFriends = {
                    comingSoon = ComingSoonContent(
                        title = friendsTitle,
                        emoji = "🤝",
                        body = friendsBody,
                    )
                },
                // Recent opponents — the social cold-start lever. Once
                // the friends graph exists this fires an outbound
                // request and the tile flips to "Sent" (already wired
                // in [RecentOpponent.requestSent]). For V1 it surfaces
                // the same explainer sheet as the friends strip so the
                // user understands the surface exists but the wiring
                // doesn't yet.
                onAddRecentOpponent = { _ ->
                    comingSoon = ComingSoonContent(
                        title = friendRequestsTitle,
                        emoji = "🤝",
                        body = friendRequestsBody,
                    )
                },
                onSeeAllRecentOpponents = {
                    comingSoon = ComingSoonContent(
                        title = recentlyPlayedTitle,
                        emoji = "🃏",
                        body = recentlyPlayedBody,
                    )
                },
                scrollState = scrollState,
            )

            if (botSetupOpen) {
                BotTableSetupDialog(
                    onStart = { difficulty, seatCount ->
                        botSetupOpen = false
                        router.navigate(
                            PlayBotsRoute(
                                difficulty = difficulty,
                                seatCount = seatCount,
                            ),
                        )
                    },
                    onDismiss = { botSetupOpen = false },
                )
            }

            comingSoon?.let { content ->
                ComingSoonSheet(
                    title = content.title,
                    body = content.body,
                    emoji = content.emoji,
                    onDismiss = { comingSoon = null },
                )
            }
        }

        // Dialog destinations live on the FloatingWindow back stack — same
        // pattern as ErrorDialogRoute in libraries:navigation. The view-model
        // navigates here when its welcome gate aligns; the dialog manages
        // its own animation/dismissal lifecycle from there.
        dialog<WelcomeDialogRoute> { backStackEntry, dialogState ->
            val route = backStackEntry.toRouteOrNull<WelcomeDialogRoute>() ?: return@dialog
            WelcomeDialog(
                state = dialogState,
                displayName = route.displayName,
                avatarEmoji = route.avatarEmoji,
                avatarBackgroundColorHex = route.avatarBackgroundColorHex,
                chips = route.chips,
                onDismiss = { router.goBack() },
            )
        }
    }
}

/**
 * Payload for the generic "this isn't built yet" sheet. Keeps the
 * Home entry point flat — one nullable state slot drives every
 * unbuilt-feature CTA, regardless of which one triggered it.
 */
private data class ComingSoonContent(
    val title: String,
    val emoji: String,
    val body: String,
)

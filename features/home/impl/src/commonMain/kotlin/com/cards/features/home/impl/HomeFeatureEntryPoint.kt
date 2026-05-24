package com.dangerfield.cards.features.home.impl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.home.WelcomeDialogRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.progression.RankDetailSheetRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.ObserveWithLifecycle
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.dialog
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.navigation.toRouteOrNull
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

            HomeScreen(
                viewModel = viewModel,
                onPlayBots = { botSetupOpen = true },
                onQuickMatch = {
                    // Spec §5.3 — Quick Match needs the public-rooms
                    // matchmaker. Until that lands, surface an honest
                    // "coming soon" sheet rather than a stub navigation.
                    comingSoon = ComingSoonContent(
                        title = "Quick Match",
                        emoji = "⚯",
                        body = "Public matchmaking ships once we have enough humans " +
                            "playing to keep wait times short. Until then, grab a " +
                            "Friend Game or a Practice table.",
                    )
                },
                // Friend Game = the existing lobby flow (create/join via
                // room code). Spec §5.2 calls this the "Friend Game"
                // entry point; the lobby screen is the actual surface.
                onFriendGame = { router.navigate(LobbyRoute()) },
                onTournament = {
                    comingSoon = ComingSoonContent(
                        title = "Tournament",
                        emoji = "♛",
                        body = "The Royal Flush Tournament arrives in V2. Quarterly " +
                            "championship — top finishers across every league.",
                    )
                },
                onTapRank = { router.navigate(RankDetailSheetRoute()) },
                onTapXp = { router.navigate(StatsRoute()) },
                onTapCash = { router.switchTab(ShopRoute()) },
                onRejoinRoom = { code -> router.navigate(LobbyRoute(prefilledCode = code)) },
                onTapFeaturedDrop = { router.switchTab(ShopRoute()) },
                // No standalone Friends surface yet — tapping the strip
                // shows what's coming and where it'll live. Once the
                // friends graph exists this routes to that surface
                // instead.
                onTapFriends = {
                    comingSoon = ComingSoonContent(
                        title = "Friends in the Hall",
                        emoji = "✦",
                        body = "Adding friends and seeing who's online ships with " +
                            "Friend Games. We'll surface the table they're at, the " +
                            "stake, and a one-tap join.",
                    )
                },
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

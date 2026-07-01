package com.dangerfield.cards.features.home.impl

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import cards.libraries.resources.generated.resources.Res
import cards.libraries.resources.generated.resources.home_coming_soon_recently_played_body
import cards.libraries.resources.generated.resources.home_coming_soon_recently_played_title
import cards.libraries.resources.generated.resources.home_coming_soon_tournament_body
import cards.libraries.resources.generated.resources.home_coming_soon_tournament_title
import cards.libraries.resources.generated.resources.ui_level_up_reward_chips
import cards.libraries.resources.generated.resources.ui_level_up_reward_cosmetic
import cards.libraries.resources.generated.resources.ui_level_up_reward_xp_boost
import org.jetbrains.compose.resources.stringResource
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.home.LevelUpRoute
import com.dangerfield.cards.features.home.PlayStyleUnlockedRoute
import com.dangerfield.cards.features.home.WelcomeDialogRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.lobby.PrivateCreateRoute
import com.dangerfield.cards.features.lobby.PrivateJoinRoute
import com.dangerfield.cards.features.rooms.PublicFindRoute
import com.dangerfield.cards.features.profile.ProfileRoute
import com.dangerfield.cards.features.progression.AchievementsRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.room.PlayBotsRoute
import com.dangerfield.cards.features.room.TutorialRoute
import com.dangerfield.cards.features.shop.ShopGraph
import com.dangerfield.cards.libraries.cards.LevelReward
import com.dangerfield.cards.libraries.cards.formatThousands
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.flowroutines.ObserveWithLifecycle
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.ui.components.LevelUpCelebration
import com.dangerfield.cards.libraries.ui.components.LevelUpReward
import com.dangerfield.cards.libraries.navigation.OnTabReselected
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.dialog
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.navigation.toRouteOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    @OptIn(ExperimentalComposeUiApi::class)
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
                    is HomeEvent.OpenPlayStyleUnlocked -> {
                        router.navigate(PlayStyleUnlockedRoute())
                    }
                }
            }
            // Routed level-up celebration. The VM derives `levelUpCelebration`
            // (survives the table→home trip + process death); we observe it and
            // navigate to the full-screen [LevelUpRoute] (no bottom bar). Firing
            // [HomeAction.MarkLevelUpShown] the instant we navigate advances the
            // watermark + clears the state, so a Home resume behind the
            // celebration can't re-navigate. `remember` keeps the derived flow
            // stable so [ObserveWithLifecycle] doesn't restart every recomposition.
            val levelUpRoute = remember(viewModel) {
                viewModel.stateFlow
                    .map { state ->
                        state.levelUpCelebration?.let { level ->
                            LevelUpRoute(
                                level = level,
                                chipsRewarded = state.levelUpRewards
                                    .filterIsInstance<LevelReward.Chips>()
                                    .firstOrNull()?.amount ?: 0L,
                                xpBoostRewarded = state.levelUpRewards.any { it is LevelReward.XpBoost },
                                cosmeticProductId = state.levelUpRewards
                                    .filterIsInstance<LevelReward.Cosmetic>()
                                    .firstOrNull()?.productId,
                            )
                        }
                    }
                    .distinctUntilChanged()
            }
            ObserveWithLifecycle(levelUpRoute) { route ->
                if (route != null) {
                    router.navigate(route)
                    viewModel.takeAction(HomeAction.MarkLevelUpShown)
                }
            }
            // The bot-table setup sheet picks difficulty *and* seat count
            // in one place, so Home's Practice hero opens it directly — no
            // intermediate difficulty picker.
            var botSetupOpen by remember { mutableStateOf(false) }
            // Private-room "create or join" sheet (SPEC §1) — transient Home
            // state, not a back-stack destination. Both branches route on
            // into the lobby (Chunk 3 swaps these for dedicated Create/Join
            // screens; today they fall through to the existing lobby paths).
            var showPrivateSheet by remember { mutableStateOf(false) }
            // "Coming soon" sheet state, parameterized so the same
            // surface serves Quick Match (not built) and Tournament
            // (V2). Null = closed.
            var comingSoon by remember { mutableStateOf<ComingSoonContent?>(null) }

            val tournamentTitle = stringResource(Res.string.home_coming_soon_tournament_title)
            val tournamentBody = stringResource(Res.string.home_coming_soon_tournament_body)
            val recentlyPlayedTitle = stringResource(Res.string.home_coming_soon_recently_played_title)
            val recentlyPlayedBody = stringResource(Res.string.home_coming_soon_recently_played_body)

            HomeScreen(
                viewModel = viewModel,
                onPlayBots = { botSetupOpen = true },
                // Public rooms → the Find flow (visual shells for now; real
                // matchmaking lands later). No coming-soon stub anymore.
                onPublicRooms = { router.navigate(PublicFindRoute()) },
                // Private room opens the create/join sheet; the choice then
                // routes on into the lobby (the seated surface).
                onPrivateRoom = { showPrivateSheet = true },
                onTournament = {
                    // Tournaments are a V2 surface — honest "coming soon".
                    comingSoon = ComingSoonContent(
                        title = tournamentTitle,
                        emoji = "🏆",
                        body = tournamentBody,
                    )
                },
                onStartTutorial = { router.navigate(TutorialRoute()) },
                // Level pill → Stats — the screen-of-record for the
                // full level / XP breakdown.
                onTapLevel = { router.navigate(StatsRoute()) },
                onTapCash = { router.switchTab(ShopGraph) },
                onRejoinRoom = { code -> router.navigate(LobbyRoute(prefilledCode = code)) },
                onTapAchievements = { router.navigate(AchievementsRoute()) },
                // The friend-requests inbox lives on the Profile tab, so the
                // strip's "N friend requests" badge (and its See-all) switch
                // there. Online presence is still stubbed, so there's no
                // standalone friends surface yet — Profile is the real inbox.
                onTapFriends = { router.switchTab(ProfileRoute()) },
                // Recent opponents — the social cold-start lever. Fires the
                // outbound friend request through the VM, which flips the tile
                // to "Sent" optimistically and reverts only if the server
                // rejects it.
                onAddRecentOpponent = { opponentId ->
                    viewModel.takeAction(HomeAction.AddFriend(opponentId))
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
                BotTableSetupSheet(
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

            if (showPrivateSheet) {
                PrivateChooseSheet(
                    onCreate = {
                        showPrivateSheet = false
                        router.navigate(PrivateCreateRoute())
                    },
                    onJoin = {
                        showPrivateSheet = false
                        router.navigate(PrivateJoinRoute())
                    },
                    onDismiss = { showPrivateSheet = false },
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

        // Play-style-unlock celebration (PROG-6). The VM fires
        // OpenPlayStyleUnlocked once its arbiter resolves this on a settled Home;
        // "See my style" pops the dialog and routes to Stats, "Later" just pops.
        dialog<PlayStyleUnlockedRoute> { _, dialogState ->
            PlayStyleUnlockedDialog(
                state = dialogState,
                onSeeStyle = {
                    router.batch {
                        goBack()
                        navigate(StatsRoute())
                    }
                },
                onDismiss = { router.goBack() },
            )
        }

        // Full-screen level-up celebration — a real destination (no bottom bar,
        // hidden because [LevelUpRoute] isn't in App.kt's `tabString()`). Back is
        // swallowed so only the explicit Continue button exits; the reward rows
        // are reconstructed from the route's aggregated prize args.
        screen<LevelUpRoute> { backStackEntry ->
            val route = backStackEntry.toRouteOrNull<LevelUpRoute>() ?: return@screen
            BackHandler { }
            val chipsLabel = stringResource(
                Res.string.ui_level_up_reward_chips,
                formatThousands(route.chipsRewarded),
            )
            val boostLabel = stringResource(Res.string.ui_level_up_reward_xp_boost)
            val cosmeticLabel = stringResource(Res.string.ui_level_up_reward_cosmetic)
            val rewards = buildList {
                if (route.chipsRewarded > 0L) add(LevelUpReward(emoji = "🪙", label = chipsLabel))
                if (route.xpBoostRewarded) add(LevelUpReward(emoji = "⚡", label = boostLabel))
                if (route.cosmeticProductId != null) add(LevelUpReward(emoji = "🎁", label = cosmeticLabel))
            }
            LevelUpCelebration(
                level = route.level,
                onContinue = { router.goBack() },
                rewards = rewards,
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

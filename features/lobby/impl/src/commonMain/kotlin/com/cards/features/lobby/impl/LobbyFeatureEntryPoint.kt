package com.dangerfield.cards.features.lobby.impl

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.lobby.LobbyRoute
import com.dangerfield.cards.features.lobby.PrivateCreateRoute
import com.dangerfield.cards.features.lobby.PrivateJoinRoute
import com.dangerfield.cards.features.lobby.RoomInvite
import com.dangerfield.cards.features.room.PlayMultiplayerRoute
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.routeDeepLink
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.snackbar.SnackbarDuration
import com.dangerfield.cards.libraries.ui.snackbar.showSnackBar
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class LobbyFeatureEntryPoint(
    private val viewModelFactory: (prefilledCode: String?, autoCreate: Boolean, maxSeats: Int?, buyIn: Long?, open: Boolean) -> LobbyViewModel,
    private val chipsRepository: ChipsRepository,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        // Private create/join front-ends (SPEC §6–7). Thin screens that
        // funnel into the seated lobby below via autoCreate / prefilledCode.
        // They pop themselves on the way in so the lobby sits directly on
        // Home; leaving the lobby returns Home, not back to this setup step.
        screen<PrivateCreateRoute> {
            val chipBalance by chipsRepository.observeBalance().collectAsStateWithLifecycle(initialValue = null)
            PrivateCreateScreen(
                chipBalance = chipBalance,
                onBack = { router.goBack() },
                onCreate = { maxPlayers, buyIn, open ->
                    router.batch {
                        popBackTo(PrivateCreateRoute::class, inclusive = true)
                        navigate(LobbyRoute(autoCreate = true, maxSeats = maxPlayers, buyIn = buyIn, open = open))
                    }
                },
            )
        }
        screen<PrivateJoinRoute> { backStackEntry ->
            val joinRoute = backStackEntry.toRoute<PrivateJoinRoute>()
            PrivateJoinScreen(
                rejectedCode = joinRoute.rejectedCode,
                onBack = { router.goBack() },
                onJoin = { code ->
                    router.batch {
                        popBackTo(PrivateJoinRoute::class, inclusive = true)
                        navigate(LobbyRoute(prefilledCode = code))
                    }
                },
            )
        }

        // Deep-link join: `cards://join/{prefilledCode}` lands a tapped invite
        // straight in the lobby with the code pre-filled, which auto-attempts a
        // join on entry (same path as PrivateJoinScreen → LobbyRoute). The code
        // is a path segment so the shared URL reads cleanly. autoCreate/open
        // default false, so an invite link never spins up a fresh room.
        screen<LobbyRoute>(
            deepLinks = listOf(
                routeDeepLink<LobbyRoute>(basePath = "${RoomInvite.DEEP_LINK_BASE_PATH}/{prefilledCode}"),
            ),
        ) { backStackEntry ->
            val route = backStackEntry.toRoute<LobbyRoute>()
            val viewModel: LobbyViewModel = viewModel {
                viewModelFactory(route.prefilledCode, route.autoCreate, route.maxSeats, route.buyIn, route.open)
            }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()

            // Route lobby events: NavigateToMultiplayer hands off to
            // the play-screen route; HostPromoted surfaces a snackbar
            // via the app-wide SnackbarHost so the remaining players
            // know who controls the next "Start hand" tap.
            LaunchedEffect(viewModel) {
                viewModel.eventFlow.collect { event ->
                    when (event) {
                        is LobbyEvent.NavigateToMultiplayer ->
                            router.navigate(PlayMultiplayerRoute(roomCode = event.roomCode))
                        is LobbyEvent.HostPromoted -> showSnackBar(
                            message = if (event.isLocalUser) "You're now the host."
                            else "${event.newHostDisplayName} is now the host.",
                            duration = SnackbarDuration.Short,
                        )
                        // The prefilled join hit an unknown room. Pop this dead
                        // lobby and land back on the code-entry screen with the bad
                        // code + inline error so the user retries in place. Match
                        // by class — this lobby was pushed with prefilledCode set,
                        // which an instance match against LobbyRoute() would silently
                        // miss, leaving a zombie lobby underneath the join screen.
                        is LobbyEvent.JoinCodeRejected -> router.batch {
                            popBackTo(LobbyRoute::class, inclusive = true)
                            navigate(PrivateJoinRoute(rejectedCode = event.code))
                        }
                    }
                }
            }

            LobbyScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
                onShareInvite = { message -> router.shareText(message) },
            )
        }
    }
}

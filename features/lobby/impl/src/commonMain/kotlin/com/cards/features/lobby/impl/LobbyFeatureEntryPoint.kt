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
import com.dangerfield.cards.features.room.PlayMultiplayerRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
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
    private val viewModelFactory: (prefilledCode: String?, autoCreate: Boolean, maxSeats: Int?) -> LobbyViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        // Private create/join front-ends (SPEC §6–7). Thin screens that
        // funnel into the seated lobby below via autoCreate / prefilledCode.
        // They pop themselves on the way in so the lobby sits directly on
        // Home; leaving the lobby returns Home, not back to this setup step.
        screen<PrivateCreateRoute> {
            PrivateCreateScreen(
                onBack = { router.goBack() },
                onCreate = { maxPlayers ->
                    router.batch {
                        popBackTo(PrivateCreateRoute(), inclusive = true)
                        navigate(LobbyRoute(autoCreate = true, maxSeats = maxPlayers))
                    }
                },
            )
        }
        screen<PrivateJoinRoute> {
            PrivateJoinScreen(
                onBack = { router.goBack() },
                onJoin = { code ->
                    router.batch {
                        popBackTo(PrivateJoinRoute(), inclusive = true)
                        navigate(LobbyRoute(prefilledCode = code))
                    }
                },
            )
        }

        screen<LobbyRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<LobbyRoute>()
            val viewModel: LobbyViewModel = viewModel {
                viewModelFactory(route.prefilledCode, route.autoCreate, route.maxSeats)
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
                    }
                }
            }

            LobbyScreen(
                state = state,
                onAction = viewModel::takeAction,
                onBack = { router.goBack() },
            )
        }
    }
}

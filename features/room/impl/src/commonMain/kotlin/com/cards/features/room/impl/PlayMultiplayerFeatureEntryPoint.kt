package com.dangerfield.cards.features.room.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.home.HomeRoute
import com.dangerfield.cards.features.progression.StatsRoute
import com.dangerfield.cards.features.room.PlayMultiplayerRoute
import com.dangerfield.cards.libraries.identity.auth.AuthRepository
import com.dangerfield.cards.libraries.identity.auth.AuthState
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Sibling to [PlayPokerFeatureEntryPoint] — same VM + screen pair,
 * but constructs a [RemotePokerSessionFactory] from the route's
 * `roomCode` instead of the bot-mode solo factory.
 *
 * The local user's id is needed to pick out their seat from each
 * `GameState` (the factory's `tableFor` projection keys off it).
 * Resolution is async — we read it once via [LaunchedEffect] and
 * render a small loading indicator until [AuthState.Authenticated]
 * lands. The cached lookup is fast on the warm path; the spinner is
 * only visible on cold starts where the auth bootstrap hasn't
 * settled yet.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class PlayMultiplayerFeatureEntryPoint(
    private val remoteFactoryFactory: (roomCode: String, localUserId: String) -> RemotePokerSessionFactory,
    private val playPokerVmFactory: (sessionFactory: PokerSessionFactory) -> PlayPokerViewModel,
    private val authRepository: AuthRepository,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<PlayMultiplayerRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PlayMultiplayerRoute>()
            // Multiplayer needs a real (server) account. If the session resolves
            // Unauthenticated — e.g. a guest whose account creation is still
            // pending (degraded) — bounce back instead of spinning forever.
            val localUserId = rememberLocalUserId(onUnauthenticated = { router.goBack() })
            if (localUserId == null) {
                LoadingPlaceholder()
                return@screen
            }
            val viewModel: PlayPokerViewModel = viewModel(
                key = "play-mp-${route.roomCode}",
            ) {
                val factory = remoteFactoryFactory(route.roomCode, localUserId)
                playPokerVmFactory(factory)
            }
            val state = viewModel.stateFlow.collectAsStateWithLifecycle().value
            // Terminal room-close pops the screen — the room is gone, so
            // there's nothing to reconnect to. The lobby/home it lands back
            // on re-observes and surfaces the closed-room state itself.
            LaunchedEffect(viewModel) {
                viewModel.eventFlow.collect { event ->
                    if (event is PlayPokerEvent.RoomClosed) router.goBack()
                }
            }
            PlayPokerScreen(
                state = state,
                onAction = viewModel::takeAction,
                // Backing out of an in-progress MP game lands on Home, not the
                // lobby the player came through. goBack() first tears down this
                // screen's VM so the room socket closes (the server frees the
                // seat after grace); switchTab then surfaces Home. The whole
                // pair runs as one batched op so a mid-teardown scope death
                // can't strand the player on a dead table.
                onBack = {
                    router.batch {
                        goBack()
                        switchTab(HomeRoute())
                    }
                },
                onTapXp = { router.navigate(StatsRoute()) },
            )
        }
    }

    @Composable
    private fun rememberLocalUserId(onUnauthenticated: () -> Unit): String? {
        var userId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            // current() suspends until auth resolves to a definitive state, so
            // an Unauthenticated result here is final — not "still loading."
            when (val current = authRepository.current()) {
                is AuthState.Authenticated -> userId = current.userId
                is AuthState.Unauthenticated -> onUnauthenticated()
            }
        }
        return userId
    }
}

@Composable
private fun LoadingPlaceholder() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

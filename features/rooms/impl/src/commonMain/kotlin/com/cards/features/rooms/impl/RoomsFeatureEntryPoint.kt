package com.dangerfield.cards.features.rooms.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import com.dangerfield.cards.features.room.PlayMultiplayerRoute
import com.dangerfield.cards.features.room.RoomKind
import com.dangerfield.cards.features.rooms.PublicFindRoute
import com.dangerfield.cards.features.rooms.PublicSearchingRoute
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hosts the PUBLIC rooms route family (SPEC §2–4). Find → Searching is now live
 * matchmaking: Find sets a buy-in range, Searching runs the real honest search
 * (real-humans-first, with the disclosed-bot fallback) and hands straight off to
 * the multiplayer table once a hand deals. Mid-hand joins are handled by the
 * server-scrubbed spectate path on the live table, not a separate screen.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class RoomsFeatureEntryPoint(
    private val searchingViewModelFactory: (minBuyIn: Long, maxBuyIn: Long) -> PublicSearchingViewModel,
    private val chipsRepository: ChipsRepository,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<PublicFindRoute> {
            val chipBalance by chipsRepository.observeBalance()
                .collectAsStateWithLifecycle(initialValue = null)
            PublicFindScreen(
                onBack = { router.goBack() },
                onFind = { minBuyIn, maxBuyIn ->
                    router.navigate(PublicSearchingRoute(minBuyIn = minBuyIn, maxBuyIn = maxBuyIn))
                },
                chipBalance = chipBalance,
            )
        }
        screen<PublicSearchingRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PublicSearchingRoute>()
            val viewModel: PublicSearchingViewModel = viewModel {
                searchingViewModelFactory(route.minBuyIn, route.maxBuyIn)
            }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()

            LaunchedEffect(viewModel) {
                viewModel.eventFlow.collect { event ->
                    when (event) {
                        // The table is dealing — hand off to the live game. Pop
                        // Find + Searching so "leave table" returns Home, not back
                        // into the radar.
                        is PublicSearchingEvent.NavigateToTable -> router.batch {
                            popBackTo(PublicFindRoute::class, inclusive = true)
                            navigate(PlayMultiplayerRoute(roomCode = event.roomCode, kind = RoomKind.Public))
                        }
                        PublicSearchingEvent.NavigateBack -> router.goBack()
                    }
                }
            }

            PublicSearchingScreen(
                state = state,
                onAction = viewModel::takeAction,
            )
        }
    }
}

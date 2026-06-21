package com.dangerfield.cards.features.rooms.impl

import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.rooms.PublicFindRoute
import com.dangerfield.cards.features.rooms.PublicLobbyRoute
import com.dangerfield.cards.features.rooms.PublicNextRoundRoute
import com.dangerfield.cards.features.rooms.PublicSearchingRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hosts the PUBLIC rooms route family (SPEC §2–4). These are visual shells —
 * there's no live matchmaking yet. The flow walks Find → Searching → Lobby; a
 * "Leave table" pops back to Find (and thus Home). NextRound is registered as
 * the mid-hand variant for review even though Searching always lands on Lobby
 * in the mock.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class RoomsFeatureEntryPoint : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<PublicFindRoute> {
            PublicFindScreen(
                onBack = { router.goBack() },
                onFind = { router.navigate(PublicSearchingRoute()) },
            )
        }
        screen<PublicSearchingRoute> {
            PublicSearchingScreen(
                onCancel = { router.goBack() },
                // Mock match → static Lobby. Pop Find + Searching first so the
                // resulting stack is Home → Lobby and "Leave table" returns to
                // Home, not back into the radar.
                onMatched = {
                    router.batch {
                        popBackTo(PublicFindRoute(), inclusive = true)
                        navigate(PublicLobbyRoute(tableId = "demo"))
                    }
                },
            )
        }
        screen<PublicLobbyRoute> {
            PublicLobbyScreen(onLeave = { router.goBack() })
        }
        screen<PublicNextRoundRoute> {
            PublicNextRoundScreen(onLeave = { router.goBack() })
        }
    }
}

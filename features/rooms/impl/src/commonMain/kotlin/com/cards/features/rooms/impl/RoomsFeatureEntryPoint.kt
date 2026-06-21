package com.dangerfield.cards.features.rooms.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.rooms.PublicFindRoute
import com.dangerfield.cards.features.rooms.PublicLobbyRoute
import com.dangerfield.cards.features.rooms.PublicNextRoundRoute
import com.dangerfield.cards.features.rooms.PublicSearchingRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.components.Screen
import com.dangerfield.cards.libraries.ui.components.room.RoomHeader
import com.dangerfield.cards.libraries.ui.components.room.RoomVisibility
import com.dangerfield.cards.libraries.ui.components.room.VisTag
import com.dangerfield.cards.libraries.ui.components.text.Text
import com.dangerfield.cards.system.AppTheme
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Hosts the PUBLIC rooms route family. These are visual shells for now —
 * matchmaking isn't wired. Chunk 5 of the rooms redesign replaces the
 * placeholder bodies with the real Find / Searching / Lobby / NextRound
 * screens; this chunk only registers the destinations so Home can route to
 * them without crashing.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class RoomsFeatureEntryPoint : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<PublicFindRoute> {
            PublicPlaceholder("Find a table", router)
        }
        screen<PublicSearchingRoute> {
            PublicPlaceholder("Finding a table", router)
        }
        screen<PublicLobbyRoute> {
            PublicPlaceholder("Public table", router)
        }
        screen<PublicNextRoundRoute> {
            PublicPlaceholder("Public table", router)
        }
    }
}

@androidx.compose.runtime.Composable
private fun PublicPlaceholder(title: String, router: Router) {
    Screen(
        topBar = {
            RoomHeader(
                title = title,
                onNavigateBack = { router.goBack() },
                right = { VisTag(kind = RoomVisibility.Public) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Public rooms are on the way.",
                typography = AppTheme.typography.Body.B500,
                color = AppTheme.colors.contentSecondary,
            )
        }
    }
}

package com.dangerfield.cards.features.progression.impl

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.progression.RankDetailSheetRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import com.dangerfield.cards.libraries.ui.components.Screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class RankDetailSheetEntryPoint(
    private val viewModelFactory: () -> RankDetailSheetViewModel,
) : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<RankDetailSheetRoute> {
            val viewModel: RankDetailSheetViewModel = viewModel { viewModelFactory() }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()

            Screen(
                topBar = { DetailTopBar(title = "Rank", onBack = router::goBack) },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                ) {
                    RankDetailSheetContent(
                        state = state,
                        onClaimAccount = {},
                    )
                }
            }
        }
    }
}

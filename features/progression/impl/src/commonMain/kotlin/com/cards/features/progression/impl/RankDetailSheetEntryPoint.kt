package com.dangerfield.cards.features.progression.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.progression.RankDetailSheetRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.bottomSheet
import com.dangerfield.cards.libraries.ui.components.dialog.bottomsheet.BasicBottomSheet
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
        bottomSheet<RankDetailSheetRoute> { _, sheetState ->
            val viewModel: RankDetailSheetViewModel = viewModel { viewModelFactory() }
            val state by viewModel.stateFlow.collectAsStateWithLifecycle()

            BasicBottomSheet(
                state = sheetState,
                onDismissRequest = { router.goBack() },
                showCloseButton = true,
                showDragHandle = false,
            ) {
                RankDetailSheetContent(
                    state = state,
                    // Claim flow lands with Phase 3 auth — for now this is a
                    // no-op so the button still renders without crashing.
                    onClaimAccount = {},
                )
            }
        }
    }
}

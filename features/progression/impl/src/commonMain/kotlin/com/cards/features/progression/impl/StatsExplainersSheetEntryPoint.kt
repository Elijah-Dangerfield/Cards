package com.dangerfield.cards.features.progression.impl

import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.progression.StatsExplainersSheetRoute
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.bottomSheet
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class StatsExplainersSheetEntryPoint : FeatureEntryPoint {

    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        bottomSheet<StatsExplainersSheetRoute> { _, sheetState ->
            StatsExplainersSheet(
                state = sheetState,
                onDismissRequest = router::goBack,
            )
        }
    }
}

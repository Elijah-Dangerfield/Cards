package com.dangerfield.cards.libraries.navigation

import androidx.navigation.NavGraphBuilder


/**
 * Entrypoint for creating a feature. Composables registered in the build function will
 * be added to the nav graph
 *
 * Each feature must be bound into a set of FeatureBuilder via multi binding
 */
interface FeatureEntryPoint {


    abstract fun NavGraphBuilder.buildNavGraph(router: Router)



}

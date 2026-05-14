package com.dangerfield.cards.features.shop.impl

import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import com.dangerfield.cards.features.shop.ShopRoute
import com.dangerfield.cards.libraries.cards.ChipsRepository
import com.dangerfield.cards.libraries.navigation.FeatureEntryPoint
import com.dangerfield.cards.libraries.navigation.Router
import com.dangerfield.cards.libraries.navigation.screen
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, multibinding = true)
@Inject
class ShopFeatureEntryPoint(
    private val chipsRepository: ChipsRepository,
) : FeatureEntryPoint {
    override fun NavGraphBuilder.buildNavGraph(router: Router) {
        screen<ShopRoute> {
            val chips by chipsRepository.observeBalance()
                .collectAsStateWithLifecycle(initialValue = ChipsRepository.STARTING_GRANT)
            ShopScreen(chips = chips)
        }
    }
}

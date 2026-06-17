package com.dangerfield.cards.features.shop.impl

import com.dangerfield.cards.features.shop.ShopCategory
import com.dangerfield.cards.features.shop.ShopDeepLinkBus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-scoped [ShopDeepLinkBus]. A [Channel.CONFLATED] channel so a scroll
 * request fired while the grid isn't on screen yet (a cold cross-tab
 * deep-link) is held until the `ShopViewModel` subscribes, while only the
 * latest target survives — consume-once, so re-entering the shop later
 * doesn't replay a stale scroll.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class ShopDeepLinkBusImpl : ShopDeepLinkBus {

    private val channel = Channel<ShopCategory>(capacity = Channel.CONFLATED)

    override val scrollRequests: Flow<ShopCategory> = channel.receiveAsFlow()

    override fun requestScrollTo(category: ShopCategory) {
        channel.trySend(category)
    }
}

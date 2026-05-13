package com.dangerfield.cards.libraries.navigation.impl

import com.dangerfield.cards.libraries.navigation.DeepLinkBridge
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Buffers up to 4 URLs in case one arrives before the App composable has
 * subscribed (cold launches via deep link land here before NavHost composes).
 * `replay = 1` ensures the most recent URL is delivered to a late collector.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DeepLinkBridgeImpl : DeepLinkBridge {

    private val _urls = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val urls: SharedFlow<String> = _urls.asSharedFlow()

    override fun emit(url: String) {
        _urls.tryEmit(url)
    }
}

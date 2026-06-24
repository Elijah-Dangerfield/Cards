package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.networking.AccessDeniedBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-scoped [AccessDeniedBus]. Same conflate-friendly buffered shape as
 * [SessionRejectionBusImpl]: a non-suspending [signalDenied] called from the
 * response validator must reach the (rare) collector without blocking the
 * network thread.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class AccessDeniedBusImpl : AccessDeniedBus {

    private val _denials = MutableSharedFlow<AccessDeniedBus.Denial>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val denials: Flow<AccessDeniedBus.Denial> = _denials.asSharedFlow()

    override fun signalDenied(denial: AccessDeniedBus.Denial) {
        _denials.tryEmit(denial)
    }
}

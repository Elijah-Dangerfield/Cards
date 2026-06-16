package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.networking.SessionRejectionBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-scoped [SessionRejectionBus]. A buffered, conflate-friendly
 * [MutableSharedFlow] so the (rare) rejection signal is delivered to the auth
 * layer's collector even though [signalRejected] is a non-suspending
 * fire-and-forget called from the token-refresh path.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SessionRejectionBusImpl : SessionRejectionBus {

    private val _rejections = MutableSharedFlow<SessionRejectionBus.Rejection>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val rejections: Flow<SessionRejectionBus.Rejection> = _rejections.asSharedFlow()

    override fun signalRejected(wasAnonymous: Boolean) {
        _rejections.tryEmit(SessionRejectionBus.Rejection(wasAnonymous = wasAnonymous))
    }
}

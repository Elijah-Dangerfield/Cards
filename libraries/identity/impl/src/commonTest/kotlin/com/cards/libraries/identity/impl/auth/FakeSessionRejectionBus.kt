package com.dangerfield.cards.libraries.identity.impl.auth

import com.dangerfield.cards.libraries.networking.SessionRejectionBus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for [SessionRejectionBus]. Records every [signalRejected] call (so
 * the token-provider tests can assert a transient failure does NOT boot) and
 * re-emits on [rejections] (so the auth-repository tests can drive a teardown).
 */
internal class FakeSessionRejectionBus : SessionRejectionBus {

    /** `wasAnonymous` for each [signalRejected] call, in order. */
    val signaled: MutableList<Boolean> = mutableListOf()

    private val _rejections = MutableSharedFlow<SessionRejectionBus.Rejection>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val rejections: Flow<SessionRejectionBus.Rejection> = _rejections.asSharedFlow()

    override fun signalRejected(wasAnonymous: Boolean) {
        signaled += wasAnonymous
        _rejections.tryEmit(SessionRejectionBus.Rejection(wasAnonymous = wasAnonymous))
    }
}

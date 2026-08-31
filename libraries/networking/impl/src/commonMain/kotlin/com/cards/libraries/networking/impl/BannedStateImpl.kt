package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.networking.AccessDeniedBus
import com.dangerfield.cards.libraries.networking.BannedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * App-scoped [BannedState]. A [MutableStateFlow] rather than the shared flow
 * [AccessDeniedBusImpl] uses, because this one is a latch to be read, not an
 * event to be delivered — a late observer must see the ban that's already in
 * effect, and the request interceptor reads [isBanned] straight off `.value`.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class BannedStateImpl : BannedState {

    private val _denial = MutableStateFlow<AccessDeniedBus.Denial?>(null)

    override val denial: StateFlow<AccessDeniedBus.Denial?> = _denial.asStateFlow()

    override val isBanned: Boolean get() = _denial.value != null

    override fun setBanned(denial: AccessDeniedBus.Denial) {
        _denial.value = denial
    }

    override fun clearBanned() {
        _denial.value = null
    }
}

package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkReachability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Tracks a run of consecutive round-trip failures. One failure is a fluke (a
 * single timeout on an otherwise-fine connection), so [isReachable] only drops
 * after [FAILURE_THRESHOLD] in a row with no intervening success — and recovers
 * the instant a request succeeds. Counter mutations are CAS-atomic via
 * [MutableStateFlow.update], so concurrent in-flight requests can report safely.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class NetworkReachabilityImpl(
    appScope: AppCoroutineScope,
) : NetworkReachability {

    private val consecutiveFailures = MutableStateFlow(0)

    override val isReachable: StateFlow<Boolean> = consecutiveFailures
        .map { it < FAILURE_THRESHOLD }
        .stateIn(appScope, SharingStarted.Eagerly, initialValue = true)

    override fun reportReachable() {
        // Cheap + idempotent on the hot path: only writes when there's a run to clear.
        if (consecutiveFailures.value != 0) consecutiveFailures.value = 0
    }

    override fun reportUnreachable() {
        consecutiveFailures.update { it + 1 }
    }

    private companion object {
        /** Two failures in a row before we call it offline — one is noise. */
        const val FAILURE_THRESHOLD = 2
    }
}

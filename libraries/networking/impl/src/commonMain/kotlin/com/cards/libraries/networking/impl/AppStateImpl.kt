package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkReachability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Production [AppState]. Combines two signals so [isOffline] reflects whether
 * the user is *actually* online, not just whether the OS thinks there's a path:
 *
 *  - the platform [ConnectivityObserver] (OS reachability), and
 *  - [NetworkReachability] (are our requests actually completing round-trips).
 *
 * Offline when **either** says so — the OS reports no path, OR our requests are
 * failing to reach the server (captive portal, dead DNS, backend down, stalled
 * socket) even though the OS thinks it's connected.
 *
 * Initial value is optimistically `false` (online). Both sources resolve within
 * a few hundred ms; starting offline-by-default would flash the banner on cold
 * launches even on perfectly good wifi.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppState::class)
@Inject
class AppStateImpl(
    connectivityObserver: ConnectivityObserver,
    reachability: NetworkReachability,
    appScope: AppCoroutineScope,
) : AppState {

    override val isOffline: StateFlow<Boolean> = combine(
        connectivityObserver.observe(),
        reachability.isReachable,
    ) { osOnline, reachable ->
        !osOnline || !reachable
    }.stateIn(appScope, SharingStarted.Eagerly, initialValue = false)

    // Block state is unwired in V1; see PreviewAppState for the contract.
    // Kept on the interface for future overlay use-cases.
    override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}

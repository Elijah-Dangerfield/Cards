package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.core.logging.KLog
import com.dangerfield.cards.libraries.core.logging.logEvent
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import com.dangerfield.cards.libraries.networking.NetworkReachability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.withIndex
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

    private val logger = KLog.withTag("AppState")

    override val isOffline: StateFlow<Boolean> = combine(
        connectivityObserver.observe(),
        reachability.isReachable,
    ) { osOnline, reachable ->
        Signals(osOnline = osOnline, reachable = reachable)
    }
        .distinctUntilChangedBy { it.isOffline }
        // One event per banner edge, with which signal drove it — ROOM-16's
        // "offline, some features unavailable" report was undiagnosable
        // because the trail carried no record of this flip. The first
        // emission is usually just the sources resolving to the presented
        // online default, so it only counts when it's a genuine
        // boot-into-offline edge.
        .withIndex()
        .filter { (index, signals) -> index > 0 || signals.isOffline }
        .onEach { (_, signals) ->
            logger.logEvent(
                "net.offline_banner",
                "visible" to signals.isOffline,
                "os_online" to signals.osOnline,
                "backend_reachable" to signals.reachable,
            )
        }
        .map { it.value.isOffline }
        .stateIn(appScope, SharingStarted.Eagerly, initialValue = false)

    // Block state is unwired in V1; see PreviewAppState for the contract.
    // Kept on the interface for future overlay use-cases.
    override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    private data class Signals(val osOnline: Boolean, val reachable: Boolean) {
        val isOffline: Boolean get() = !osOnline || !reachable
    }
}

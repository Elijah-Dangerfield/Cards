package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.AppState
import com.dangerfield.cards.libraries.flowroutines.AppCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Production [AppState]. Subscribes to the platform [ConnectivityObserver]
 * eagerly so [isOffline] reflects the device's current reachability for
 * the entire app lifetime.
 *
 * Initial value is optimistically `false` (online). The connectivity
 * observer emits a real value within a few hundred milliseconds of boot;
 * starting offline-by-default would briefly flash the banner on cold
 * launches even when the user has perfectly good wifi.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, boundType = AppState::class)
@Inject
class AppStateImpl(
    connectivityObserver: ConnectivityObserver,
    appScope: AppCoroutineScope,
) : AppState {

    override val isOffline: StateFlow<Boolean> = connectivityObserver
        .observe()
        .map { online -> !online }
        .stateIn(appScope, SharingStarted.Eagerly, initialValue = false)

    // Block state is unwired in V1; see PreviewAppState for the contract.
    // Kept on the interface for future overlay use-cases.
    override val isBlockActive: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
}

package com.dangerfield.cards.features.upgrade

import com.dangerfield.cards.libraries.appconfig.AppConfig
import com.dangerfield.cards.libraries.appconfig.MaintenanceState

/**
 * Derived state describing whether the app shell should let the user reach
 * the navigation graph this frame, or whether a blocking modal should cover
 * everything.
 *
 * Compute with [AppGuardState.from] given the current [AppConfig] and the
 * running client's version code.
 */
sealed interface AppGuardState {

    /** Nothing to do — the app is in a normal state. */
    data object Normal : AppGuardState

    /** Server is under maintenance and we're blocking access until it lifts. */
    data class MaintenanceBlocking(val message: String) : AppGuardState

    /** Server announced maintenance but it's not blocking — show a banner. */
    data class MaintenanceBanner(val message: String) : AppGuardState

    /** Running client is below the minimum supported version. Block until upgrade. */
    data object UpgradeRequired : AppGuardState

    companion object {
        fun from(config: AppConfig, clientVersionCode: Int): AppGuardState {
            if (clientVersionCode < config.minSupportedClientVersionCode) {
                return UpgradeRequired
            }
            return when (val m = config.maintenance) {
                MaintenanceState.Off -> Normal
                is MaintenanceState.Blocking -> MaintenanceBlocking(m.message)
                is MaintenanceState.Banner -> MaintenanceBanner(m.message)
            }
        }
    }
}

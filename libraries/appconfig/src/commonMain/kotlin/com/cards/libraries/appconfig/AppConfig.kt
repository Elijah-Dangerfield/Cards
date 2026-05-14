package com.dangerfield.cards.libraries.appconfig

import kotlinx.serialization.Serializable

/**
 * Server-driven configuration fetched on launch and refreshed on resume.
 *
 * Changes here become live without a release. Use for:
 *   - The force-upgrade kill switch (`minSupportedClientVersionCode`)
 *   - Maintenance announcements (`maintenance`)
 *   - Game-economy knobs (`startingChipGrant`, `botXpMultiplier`, etc.)
 *   - Ad-hoc kill switches (`featureUnlocks`)
 *
 * This is NOT a feature-flagging system — there's no targeting and no rollout
 * percentages, just server-driven values that every client sees the same way.
 *
 * The client falls back to [Defaults] when offline on a fresh install and to
 * the last cached value on every subsequent launch, so AppConfig should never
 * be the reason the app fails to load.
 */
@Serializable
data class AppConfig(
    val minSupportedClientVersionCode: Int = 1,
    val maintenance: MaintenanceState = MaintenanceState.Off,
    val startingChipGrant: Long = 10_000,
    val anonymousChipGrant: Long = 2_000,
    val botXpMultiplier: Double = 0.5,
    val turnTimerSecondsDefault: Int = 30,
    val emoteCooldownMs: Long = 2_000,
    val featureUnlocks: Map<String, Boolean> = emptyMap(),
) {
    fun isFeatureEnabled(key: String, default: Boolean = false): Boolean =
        featureUnlocks[key] ?: default

    companion object {
        val Defaults: AppConfig = AppConfig()
    }
}

@Serializable
sealed class MaintenanceState {

    @Serializable
    data object Off : MaintenanceState()

    @Serializable
    data class Banner(val message: String) : MaintenanceState()

    @Serializable
    data class Blocking(val message: String) : MaintenanceState()
}

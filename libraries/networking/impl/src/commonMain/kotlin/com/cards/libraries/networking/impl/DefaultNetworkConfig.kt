package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.networking.NetworkConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default network config for local development. Points each platform's
 * simulator/emulator at the Ktor server running on the host machine.
 *
 * Host aliases differ by platform:
 *  - **Android emulator**: `10.0.2.2` is the magic alias for the host's
 *    loopback. `localhost` would resolve to the emulator itself.
 *  - **iOS simulator**: shares the host's network namespace, so `localhost`
 *    just works.
 *  - **Physical device**: neither works — set up `adb reverse` (Android) or
 *    point at the host's LAN IP (iOS), or deploy a dev API and use that.
 *
 * For deployed environments (dev/staging/prod), bind a project-specific
 * `NetworkConfig` with `replaces = [DefaultNetworkConfig::class]` — likely
 * gated on a release-channel BuildConfig once we have proper build variants.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultNetworkConfig : NetworkConfig {
    override val baseUrl: String = when (BuildInfo.platform) {
        Platform.Android -> "http://10.0.2.2:8080"
        Platform.iOS -> "http://localhost:8080"
    }
}

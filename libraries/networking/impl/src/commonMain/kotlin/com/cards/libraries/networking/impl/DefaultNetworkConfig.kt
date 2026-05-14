package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.networking.NetworkConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default network config. Override by binding your own `NetworkConfig` with
 * `replaces = [DefaultNetworkConfig::class]` once we have proper build variants
 * for dev / prod with their own BuildConfig values.
 *
 * 10.0.2.2 is the Android emulator's loopback to the host machine, so
 * `./gradlew :apps:server:run` on the host is reachable from a debug build.
 * iOS simulator + physical devices need a real host (LAN IP or a deployed URL).
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultNetworkConfig : NetworkConfig {
    override val baseUrl: String = "http://10.0.2.2:8080"
}

package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import com.dangerfield.cards.libraries.core.Platform
import com.dangerfield.cards.libraries.core.ServerInfo
import com.dangerfield.cards.libraries.networking.NetworkConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default network config. URL selection:
 *
 *  1. `server.useLocal=true` (per-dev, via `local.properties`) → point at
 *     the dev's own machine, choosing the right loopback for the running
 *     platform (`localhost:8080` on iOS sim, `10.0.2.2:8080` on Android
 *     emulator).
 *  2. Otherwise, pick by build type:
 *       - debug → [ServerInfo.DEV_BASE_URL]
 *       - release → [ServerInfo.PROD_BASE_URL]
 *
 * See `ServerInfo` and `Versioning.kt#loadServerMetadata` for the
 * property plumbing and CI guard.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultNetworkConfig : NetworkConfig {
    override val baseUrl: String = when {
        ServerInfo.useLocal -> when (BuildInfo.platform) {
            Platform.iOS -> ServerInfo.LOCAL_URL_IOS
            Platform.Android -> ServerInfo.LOCAL_URL_ANDROID
        }
        BuildInfo.isDebug -> ServerInfo.DEV_BASE_URL
        else -> ServerInfo.PROD_BASE_URL
    }
}

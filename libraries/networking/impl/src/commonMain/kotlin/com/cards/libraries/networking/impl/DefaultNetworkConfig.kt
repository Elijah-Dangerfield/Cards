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
 * Default network config. When [ServerInfo.useLocal] is true (per-dev
 * flag in `local.properties`), points at the dev's own machine using a
 * platform-aware loopback — `http://localhost:8080` on iOS sim,
 * `http://10.0.2.2:8080` on Android emulator. Otherwise reads the URL
 * from [ServerInfo.baseUrl], which is sourced from `gradle.properties`
 * (team default, checked in) and overridable via `local.properties` or
 * the `CARDS_SERVER_BASE_URL` env var.
 *
 * See `ServerInfo` and `Versioning.kt#loadServerMetadata` for details.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultNetworkConfig : NetworkConfig {
    override val baseUrl: String = if (ServerInfo.useLocal) {
        when (BuildInfo.platform) {
            Platform.iOS -> ServerInfo.LOCAL_URL_IOS
            Platform.Android -> ServerInfo.LOCAL_URL_ANDROID
        }
    } else {
        ServerInfo.baseUrl
    }
}

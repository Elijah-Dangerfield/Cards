package com.dangerfield.cards.libraries.networking.impl

import com.dangerfield.cards.libraries.core.ServerInfo
import com.dangerfield.cards.libraries.networking.NetworkConfig
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Default network config. Resolves the server base URL via [ServerInfo],
 * which is generated from a build-time gradle property — default is the
 * deployed Fly dev server, overridable per-dev via `server.baseUrl` in
 * `local.properties` (gitignored) for local backend work. See
 * `ServerInfo` and `Versioning.kt#loadServerMetadata` for details.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultNetworkConfig : NetworkConfig {
    override val baseUrl: String = ServerInfo.baseUrl
}

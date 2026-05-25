package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

/**
 * Build-time configuration for the Cards game server. Sourced from
 * `server.baseUrl` in `local.properties` (per-dev override) or the
 * `CARDS_SERVER_BASE_URL` env var, falling back to the deployed Fly dev
 * server. See `Versioning.kt#loadServerMetadata` for the resolution chain.
 */
object ServerInfo {
    val baseUrl: String
        get() = CardsBuildConfig.SERVER_BASE_URL
}

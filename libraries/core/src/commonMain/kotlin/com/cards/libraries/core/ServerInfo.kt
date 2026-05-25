package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

/**
 * Build-time configuration for the Cards game server. Resolved from
 * `local.properties` (per-dev override) → `CARDS_SERVER_BASE_URL` env var
 * → `gradle.properties` (team default, checked in) → hardcoded fallback.
 * See `Versioning.kt#loadServerMetadata` for the resolution chain.
 */
object ServerInfo {
    val baseUrl: String
        get() = CardsBuildConfig.SERVER_BASE_URL
}

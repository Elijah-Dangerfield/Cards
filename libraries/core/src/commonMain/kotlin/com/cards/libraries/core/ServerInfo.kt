package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

/**
 * Build-time configuration for the Cards game server. The two knobs are
 * resolved at build time by `Versioning.kt#loadServerMetadata`:
 *
 *  - [useLocal] — per-dev toggle (`server.useLocal=true` in
 *    `local.properties`). When true, [DefaultNetworkConfig] ignores
 *    [baseUrl] and points at the dev's own machine, choosing
 *    `http://localhost:8080` (iOS) or `http://10.0.2.2:8080` (Android)
 *    based on the running platform.
 *
 *  - [baseUrl] — explicit URL used when [useLocal] is false. Resolution:
 *    `local.properties` → `CARDS_SERVER_BASE_URL` env → `gradle.properties`
 *    (team default, checked in) → hardcoded fallback.
 */
object ServerInfo {
    val baseUrl: String
        get() = CardsBuildConfig.SERVER_BASE_URL

    val useLocal: Boolean
        get() = CardsBuildConfig.SERVER_USE_LOCAL

    /** iOS sim → host. Used when [useLocal] is true on iOS. */
    const val LOCAL_URL_IOS: String = "http://localhost:8080"

    /** Android emulator → host (10.0.2.2 is the emulator's loopback to host). */
    const val LOCAL_URL_ANDROID: String = "http://10.0.2.2:8080"
}

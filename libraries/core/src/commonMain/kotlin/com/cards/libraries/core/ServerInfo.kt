package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

/**
 * Local-dev server override. The normal dev/prod targets live in [AppEnvironment]
 * (picked by build type); this only governs the `server.useLocal` escape hatch
 * that points the client at the developer's own machine.
 *
 * [useLocal] mirrors the `server.useLocal` gradle property (default false,
 * flippable per-dev via local.properties). When true, the client ignores the
 * [AppEnvironment] base URL and targets a platform-aware loopback
 * ([LOCAL_URL_IOS] / [LOCAL_URL_ANDROID]). See `Versioning.kt#loadServerMetadata`
 * for the property resolution and CI guard.
 */
object ServerInfo {
    /** iOS sim → host. Used when [useLocal] is true on iOS. */
    const val LOCAL_URL_IOS: String = "http://localhost:8080"

    /** Android emulator → host (10.0.2.2 is the emulator's loopback to host). */
    const val LOCAL_URL_ANDROID: String = "http://10.0.2.2:8080"

    val useLocal: Boolean
        get() = CardsBuildConfig.SERVER_USE_LOCAL
}

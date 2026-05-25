package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

/**
 * Where the Cards client points its Ktor calls. Two concerns live here:
 *
 *  - Build-type URLs: [DEV_BASE_URL] / [PROD_BASE_URL] are the
 *    hardcoded targets the client uses in normal operation, switched by
 *    `BuildInfo.isDebug` in `DefaultNetworkConfig`. Editing one of these
 *    URLs is a code change — that's deliberate, so changing where
 *    release builds talk to goes through review.
 *
 *  - Local-dev override: [useLocal] mirrors the `server.useLocal`
 *    gradle property (default false, flippable per-dev via
 *    local.properties). When true, the client ignores the build-type
 *    URLs and targets the dev's own machine using a platform-aware
 *    loopback ([LOCAL_URL_IOS] / [LOCAL_URL_ANDROID]).
 *
 * See `Versioning.kt#loadServerMetadata` for the property resolution.
 */
object ServerInfo {
    /** Debug builds talk here. */
    const val DEV_BASE_URL: String = "https://cards-server-dev.fly.dev"

    /**
     * Release builds talk here. TODO: spin up a real prod Fly app and
     * point this at it; for now it matches the dev URL so release builds
     * still work end-to-end.
     */
    const val PROD_BASE_URL: String = "https://cards-server-dev.fly.dev"

    /** iOS sim → host. Used when [useLocal] is true on iOS. */
    const val LOCAL_URL_IOS: String = "http://localhost:8080"

    /** Android emulator → host (10.0.2.2 is the emulator's loopback to host). */
    const val LOCAL_URL_ANDROID: String = "http://10.0.2.2:8080"

    val useLocal: Boolean
        get() = CardsBuildConfig.SERVER_USE_LOCAL
}

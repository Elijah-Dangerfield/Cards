package com.dangerfield.cards.libraries.core

import com.dangerfield.cards.buildinfo.CardsBuildConfig

/**
 * JVM actual for [BuildInfo], used by the Ktor server (apps:server). The
 * server doesn't care about debug-vs-release flags or platform branching
 * — it just needs the file to exist so the JVM target compiles. Values
 * fall back to the same generated [CardsBuildConfig] as the other
 * platforms so anything that logs version metadata still works.
 *
 * [platform] is stubbed to [Platform.Android] as a known compromise:
 * the `Platform` enum has only `Android`/`iOS` and server code never
 * branches on this field. Adding `Platform.JVM` would cascade
 * non-exhaustive-when warnings into every client switch. Revisit if a
 * server consumer ever genuinely needs to distinguish itself.
 */
actual object BuildInfo {
    actual val isDebug: Boolean = false

    actual val platform: Platform = Platform.Android

    actual val applicationId: String
        get() = CardsBuildConfig.APPLICATION_ID

    actual val versionName: String
        get() = CardsBuildConfig.VERSION_NAME

    actual val versionCode: Int
        get() = CardsBuildConfig.VERSION_CODE

    actual val releaseChannel: String
        get() = CardsBuildConfig.RELEASE_CHANNEL

    actual val buildNumber: Int
        get() = CardsBuildConfig.BUILD_NUMBER

    actual val commitSha: String
        get() = CardsBuildConfig.COMMIT_SHA

    actual val commitBranch: String
        get() = CardsBuildConfig.COMMIT_BRANCH
}

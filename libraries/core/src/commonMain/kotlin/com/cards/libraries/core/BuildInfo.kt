package com.dangerfield.cards.libraries.core

expect object BuildInfo {
    val isDebug: Boolean
    val platform: Platform
    val applicationId: String
    val versionName: String
    val versionCode: Int
    val releaseChannel: String
    val buildNumber: Int

    /** Short git SHA the build was produced from — `GITHUB_SHA` in CI,
     *  `git rev-parse` locally, `"unknown"` when neither is available. */
    val commitSha: String

    /** Branch the build was produced from (`GITHUB_REF_NAME` in CI). */
    val commitBranch: String
}

fun BuildInfo.isiOS() = BuildInfo.platform == Platform.iOS
val BuildInfo.buildType: String get() = if (BuildInfo.isDebug) "debug" else "release"
val BuildInfo.versionTag: String get() = "${BuildInfo.versionName}-${BuildInfo.releaseChannel}"
fun BuildInfo.versionString(): String = "$versionName ($buildNumber)"

/**
 * Human-facing build identifier for the Settings/About row. Prod shows a clean
 * `"0.1.0 (247)"`; any non-prod channel (a `beta.yml` internal build, a dev
 * build) appends the channel — `"0.1.0 (247) · beta"` — so a tester can read
 * off exactly which build they're on when reporting an issue. The build number
 * is CI-monotonic (see `loadVersionMetadata`), so it maps back to a commit.
 */
fun BuildInfo.versionDisplay(): String =
    versionString() + if (releaseChannel != "prod") " · $releaseChannel" else ""


enum class Platform {
    Android,
    iOS
}
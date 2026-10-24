package com.dangerfield.cards.server.domain

import com.dangerfield.cards.libraries.core.BuildInfo
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Which build this process is running, and since when.
 *
 * Prod has twice served weeks-old code with every dashboard green, because
 * the only alert watching the server asked whether it was *silent* — and it
 * was answering happily, from a stale image. Nothing could tell the
 * difference, because the running process never said what it was. `/_health`
 * now reports this, so a dashboard panel, an alert, or a routine can diff it
 * against `origin/main`.
 *
 * [commitSha] and [commitBranch] come from [BuildInfo], baked in at build
 * time by `loadVersionMetadata` (`build-logic/.../Versioning.kt`). The
 * server-only Docker image has no `.git` directory, so the Dockerfile takes
 * the SHA as a `COMMIT_SHA` build arg and exports it as `GITHUB_SHA` for the
 * in-container Gradle build to pick up. Without that it resolves to
 * `"unknown"` — honest, but useless for the comparison above.
 *
 * [startedAt] is the JVM process start read from [ProcessHandle], not a
 * timestamp captured when something first touched the endpoint, so "how long
 * has this been up" doesn't depend on who asked first.
 */
data class ServerBuild(
    val commitSha: String,
    val commitBranch: String,
    val version: String,
    val startedAt: Instant,
) {
    companion object {
        val current: ServerBuild by lazy {
            ServerBuild(
                commitSha = BuildInfo.commitSha,
                commitBranch = BuildInfo.commitBranch,
                version = BuildInfo.versionName,
                startedAt = processStartedAt(),
            )
        }

        /**
         * `startInstant()` is empty when the OS won't report it (not on Fly's
         * Linux machines, but the contract is an `Optional`), so fall back to
         * first-touch rather than refusing to answer.
         */
        private fun processStartedAt(): Instant =
            ProcessHandle.current().info().startInstant().orElse(null)
                ?.toEpochMilli()
                ?.let(Instant::fromEpochMilliseconds)
                ?: Clock.System.now()
    }
}

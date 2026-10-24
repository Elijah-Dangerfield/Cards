package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.domain.ServerBuild
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Liveness/readiness probe. Stays /_health (not /v1/_health) so it's not tied
 * to a versioned API and load balancers can hit it unconditionally.
 *
 * It also reports which build is answering (see [ServerBuild]). That is the
 * half of staleness detection that was missing: Fly's check and the deploy
 * smoke tests only ever asked whether *something* was alive, so prod served
 * weeks-old code twice with every dashboard green. With the SHA in the body, a
 * dashboard, an alert, or a routine can compare it to `origin/main`.
 *
 * **Deliberately unauthenticated**, like the probe always was. Fly's health
 * check can't carry a token, and an external uptime monitor shouldn't need
 * one. What leaks is a commit SHA, a version and a process start time for a
 * repo that is public anyway — the SHA names a commit anyone can already read
 * on GitHub. Keep it that way: no env var names, no dependency or database
 * status, no counts. Anything that would actually tell an attacker something
 * belongs behind `X-Admin-Token` in [adminRoutes].
 */
fun Route.healthRoutes(
    build: ServerBuild = ServerBuild.current,
    clock: Clock = Clock.System,
) {
    get("/_health") {
        call.respond(
            HealthResponse(
                commitSha = build.commitSha,
                commitBranch = build.commitBranch,
                version = build.version,
                startedAt = build.startedAt.toString(),
                uptimeSeconds = (clock.now() - build.startedAt).inWholeSeconds.coerceAtLeast(0),
            ),
        )
    }
}

/**
 * `ok` predates everything else here and is the only field anything depends
 * on today — Fly's check and both deploy smoke tests read the status code, and
 * `SmallRoutesTest` pins `ok`. The rest is additive; adding more is safe,
 * renaming or dropping these is not.
 */
@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    /** Short git SHA (12 chars), or `"unknown"` if the build wasn't told. */
    val commitSha: String,
    val commitBranch: String,
    val version: String,
    /** ISO-8601 instant the JVM process started. */
    val startedAt: String,
    val uptimeSeconds: Long,
)

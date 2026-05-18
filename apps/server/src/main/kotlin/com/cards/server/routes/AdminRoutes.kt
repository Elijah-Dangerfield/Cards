package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.domain.OrphanAnonymousSweep
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days

/**
 * Token-gated cleanup + maintenance endpoints. Not part of the public
 * client surface — designed to be triggered from a scheduler (GitHub
 * Actions cron, Fly cron, external uptime monitor with auth header).
 *
 * Auth: bearer-style `X-Admin-Token: <ADMIN_API_TOKEN>`. We don't use the
 * JWT plugin here because the caller is a machine, not a Supabase user.
 *
 * Cadence recommendation (V1): daily for the anon sweep. Two-line cron-
 * style GitHub Actions workflow at the repo root is the easiest path —
 * see DEPLOY.md.
 */
fun Route.adminRoutes(config: AdminConfig, sweep: OrphanAnonymousSweep) {
    route("/v1/admin") {
        post("/sweep-anonymous-users") {
            if (!call.authenticatedAsAdmin(config)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    problemEnvelope("unauthorized", "Missing or invalid admin token."),
                )
            }
            val result = sweep.run(maxInactiveAge = config.orphanAnonTtlDays.days)
            call.respond(
                if (result.notConfigured) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
                SweepResponse(
                    candidatesFound = result.candidatesFound,
                    deleted = result.deleted,
                    failedToDelete = result.failedToDelete,
                    notConfigured = result.notConfigured,
                ),
            )
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.authenticatedAsAdmin(config: AdminConfig): Boolean {
    val token = config.apiToken?.takeUnless { it.isBlank() } ?: return false
    val presented = request.header("X-Admin-Token") ?: return false
    // Constant-time compare to avoid timing leaks. Length differences are
    // OK to short-circuit on — the attacker learns the expected length
    // either way and it's not load-bearing.
    if (presented.length != token.length) return false
    var mismatch = 0
    for (i in token.indices) mismatch = mismatch or (token[i].code xor presented[i].code)
    return mismatch == 0
}

@Serializable
private data class SweepResponse(
    val candidatesFound: Int,
    val deleted: Int,
    val failedToDelete: Int,
    val notConfigured: Boolean,
)

private fun problemEnvelope(code: String, message: String): Map<String, Map<String, String>> =
    mapOf("error" to mapOf("code" to code, "message" to message))

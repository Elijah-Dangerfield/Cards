package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import com.dangerfield.cards.server.plugins.clientIp
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AdminAuth")

/**
 * Shared gate for the token-protected admin routes under `/v1/admin`. The
 * caller is a machine (cron, the local config admin UI), not a Supabase user,
 * so these routes use a bearer-style `X-Admin-Token: <ADMIN_API_TOKEN>` header
 * instead of the JWT plugin. The compare itself lives on
 * [AdminConfig.matchesApiToken], which the rate limiter shares.
 *
 * Every rejection emits one WARN so a brute force against the chip-minting
 * routes is queryable in Loki instead of invisible (ENG-41) — an unauthenticated
 * scanner probed `/v1/admin/grant-chips` on prod in 2026-08 and left no trace on
 * any dashboard. The presented token is **never** logged, only whether one was
 * offered: logging it would put a near-miss guess in the log store, and the log
 * store is a much softer target than the secret store.
 */
internal fun ApplicationCall.authenticatedAsAdmin(config: AdminConfig): Boolean {
    val presented = request.header("X-Admin-Token")
    if (config.matchesApiToken(presented)) return true

    val reason = when {
        config.apiToken.isNullOrBlank() -> "no admin token configured server-side"
        presented == null -> "no token presented"
        else -> "token mismatch"
    }
    logger.warn(
        "Rejected admin request: {} {} from {} ({})",
        request.httpMethod.value,
        request.path(),
        clientIp(),
        reason,
    )
    return false
}

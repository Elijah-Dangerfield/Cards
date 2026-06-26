package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.config.AdminConfig
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header

/**
 * Shared gate for the token-protected admin routes under `/v1/admin`. The
 * caller is a machine (cron, the local config admin UI), not a Supabase user,
 * so these routes use a bearer-style `X-Admin-Token: <ADMIN_API_TOKEN>` header
 * instead of the JWT plugin.
 *
 * Returns false (→ 401) when no token is configured server-side or the header
 * is missing / wrong. The compare is constant-time over the expected length to
 * avoid leaking it a character at a time; the length short-circuit is not
 * load-bearing (an attacker learns the length from a timing oracle either way).
 */
internal fun ApplicationCall.authenticatedAsAdmin(config: AdminConfig): Boolean {
    val token = config.apiToken?.takeUnless { it.isBlank() } ?: return false
    val presented = request.header("X-Admin-Token") ?: return false
    if (presented.length != token.length) return false
    var mismatch = 0
    for (i in token.indices) mismatch = mismatch or (token[i].code xor presented[i].code)
    return mismatch == 0
}

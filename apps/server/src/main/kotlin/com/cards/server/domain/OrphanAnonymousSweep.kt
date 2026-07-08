package com.dangerfield.cards.server.domain

import kotlin.time.Duration

/**
 * Periodic clean-up for anonymous Supabase users we created but the user
 * never returned to. Per the 2026-05-18 "Identity pivot (REVERSED)"
 * decision, signing in to a pre-existing OAuth account from an anon
 * session orphans the anon row — Supabase doesn't auto-merge. Without a
 * sweep, `auth.users` would grow unbounded.
 *
 * Implementation deletes via the same admin path that powers DELETE
 * /v1/me — so each removed user's profile row also gets reaped. Returns
 * a summary so the caller can log / surface metrics.
 *
 * V1 V cadence: not running automatically inside the server (Fly's
 * auto-stop-when-idle makes in-process timers unreliable). Trigger via
 * `POST /v1/admin/sweep-anonymous-users` with the admin token; document
 * the curl-from-cron pattern for production.
 */
interface OrphanAnonymousSweep {
    suspend fun run(maxInactiveAge: Duration): SweepResult
}

/**
 * Summary of a single sweep run. [skipped] counts candidates the TTL query
 * matched but the shared never-delete-progress verification preserved (IAP
 * spend, engagement inventory, meaningful XP, or an active room seat).
 */
data class SweepResult(
    val candidatesFound: Int,
    val deleted: Int,
    val failedToDelete: Int,
    val notConfigured: Boolean,
    val skipped: Int = 0,
)

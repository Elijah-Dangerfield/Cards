package com.dangerfield.cards.server.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Read-side view of a user's moderation standing.
 *
 * V1 source of truth is the native Supabase flag `auth.users.banned_until`:
 * a dashboard ban (or the Admin API) sets a future timestamp; a clear sets
 * it to null or a past timestamp. That column carries no reason or appeal
 * URL, so [BanStatus.reason] is always [BanReason.Banned] today — a richer
 * suspended-vs-banned distinction needs an app-level moderation table, which
 * is deliberately out of this slice (see docs/todo.md).
 */
interface ModerationRepository {
    /**
     * The caller's current ban standing, or null if they are in good standing
     * (no `banned_until`, or a `banned_until` already in the past).
     */
    suspend fun banStatusFor(userId: UserId): BanStatus?
}

/**
 * Why a user is blocked + when (if ever) the block lifts. Mirrors the locked
 * wire contract the client reads off a `403` (see
 * [com.dangerfield.cards.server.plugins.AccessDeniedResponse]).
 */
@OptIn(ExperimentalTime::class)
data class BanStatus(
    val reason: BanReason,
    /** When the block lifts, or null for an indefinite block. */
    val until: Instant?,
)

enum class BanReason(val wire: String) {
    Banned("banned"),
    Suspended("suspended"),
}

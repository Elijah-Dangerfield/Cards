package com.dangerfield.cards.server.domain

import java.util.UUID

/**
 * L1 install_id-based orphan cleanup. Fired opportunistically from the
 * `/v1/me` handler once we know the caller's current install_id. Finds
 * sibling anonymous profile rows tagged with the same install_id and
 * deletes the ones that look abandoned — the "user signed out and signed
 * into a different account on the same install" case from
 * [docs/recovery-and-orphaned-accounts.md](../../../../../../../../docs/recovery-and-orphaned-accounts.md).
 *
 * Distinct from [OrphanAnonymousSweep], which runs on a TTL via the
 * admin endpoint and doesn't know about install_id. This sweep is cheap
 * enough to fire on every authed `/v1/me`: the pre-filter is an indexed
 * SQL query on `profiles.install_id` plus a LEFT JOIN against
 * `wallet_events.reason LIKE 'iap.%'`; the typical result is zero
 * candidates and one round-trip.
 *
 * Implementations are responsible for per-candidate verification (still
 * anonymous, no engagement-grade inventory, no active room seat) before
 * deleting; see [DefaultOrphanInstallSweep].
 */
interface OrphanInstallSweep {
    suspend fun run(currentInstallId: UUID, currentUserId: UserId): InstallSweepResult
}

/**
 * Summary of a single sweep run. Surfaced so the caller can log /
 * surface metrics; the route fire-and-forget path discards the result.
 *
 * - [candidatesFound] — rows returned by the SQL pre-filter.
 * - [deleted] — candidates that passed Kotlin verification AND whose
 *   Supabase admin delete succeeded (or returned AlreadyGone).
 * - [skipped] — candidates that the SQL caught but Kotlin verification
 *   rejected (still has engagement-grade inventory, sat in an active
 *   room, etc.).
 * - [failedToDelete] — verified candidates whose admin delete failed.
 *   The next sweep retries.
 * - [notConfigured] — true when the admin client short-circuited on a
 *   missing service-role key; the caller can log + skip metrics.
 */
data class InstallSweepResult(
    val candidatesFound: Int,
    val deleted: Int,
    val skipped: Int,
    val failedToDelete: Int,
    val notConfigured: Boolean,
)

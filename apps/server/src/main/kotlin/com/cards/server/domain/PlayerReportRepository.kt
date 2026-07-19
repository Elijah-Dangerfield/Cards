package com.dangerfield.cards.server.domain

/**
 * Server-of-record for player reports (MOD-1). Backs the in-app "Report a
 * player" action: a report is an append-only row a human moderator reads
 * later. No auto-ban, no dedup — the route's rate limit bounds abuse, and a
 * reporter may report the same user more than once (e.g. across rooms).
 *
 * See `V85__player_reports.sql` + [com.dangerfield.cards.server.db.PlayerReportsTable].
 */
interface PlayerReportRepository {

    /**
     * Record [reporter]'s report of [reported], captured [inRoom] (null when
     * the context isn't a room) with an optional free-text [reason] and the
     * reporter's selected reason [categories] (canonical keys, already
     * sanitized/capped by the caller; empty when none were picked). Callers
     * validate that [reporter] and [reported] differ before calling.
     */
    suspend fun record(
        reporter: UserId,
        reported: UserId,
        inRoom: String?,
        reason: String?,
        categories: List<String>,
    )
}

package com.dangerfield.cards.libraries.cards

/**
 * Server-only lifetime stats the client can't derive locally — today just the
 * count of distinct opponents the user has played against (multiplayer only;
 * bot hands don't populate the opponents table server-side).
 *
 * Always-network read: the server owns the count and there's no local source to
 * project it from. Callers fetch on screen entry and tolerate a null until it
 * lands. Not part of [ProgressionRepository] because progression has a local
 * write-through ledger this stat has no counterpart for.
 */
interface LifetimeStatsRepository {

    /**
     * `GET /v1/me/stats` → the number of distinct opponents the user has played
     * against. A failure (offline, server error) returns a failed [Result]; the
     * caller keeps showing the last known value or nothing.
     */
    suspend fun fetchDistinctOpponents(): Result<Long>
}

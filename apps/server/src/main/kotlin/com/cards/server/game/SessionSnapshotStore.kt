package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.GameState
import java.util.UUID
import kotlin.time.Instant

/**
 * Durable snapshot of a live [GameSession]'s state, one row per active
 * room session. Overwritten inside the per-session mutex on every
 * mutation so the registry can hydrate a fresh in-memory cache after a
 * server restart and reconnecting players see seats / stacks / betting
 * round intact.
 *
 * The store is keyed by both the stable session UUID (primary key) and
 * the six-char room code the registry indexes today. The code column
 * exists because §B2's `rooms` table hasn't landed yet — once it does,
 * the code will live there and `room_sessions.session_id` will FK to it.
 *
 * Failures: writes are best-effort under the mutex. A persistence
 * failure logs at the call site but does not roll back the in-memory
 * mutation — losing a single un-persisted frame on a server crash is
 * preferable to rejecting a valid player intent because the DB
 * hiccupped. The next successful mutation overwrites the snapshot in
 * full, so the DB catches up automatically.
 */
interface SessionSnapshotStore {

    suspend fun upsert(snapshot: SessionSnapshot)

    /**
     * Look up the durable snapshot for [code]. Returns null when no
     * session has ever persisted state for that room — the registry
     * treats that as "fresh session, no prior state."
     */
    suspend fun readByCode(code: String): SessionSnapshot?

    /** Drop the row for [code]. Called when [GameSessionRegistry.end] tears a session down. */
    suspend fun deleteByCode(code: String)
}

data class SessionSnapshot(
    val sessionId: UUID,
    val code: String,
    val state: GameState,
    /**
     * Each player's stack as of the last hand they were seated for, kept even
     * after they bust out and are dropped from the deal — the durable mirror of
     * [GameSession]'s in-memory `lastKnownStacks`. The boot recovery sweep reads
     * this when a session's user holds no live seat in [state] (busted + dropped):
     * without it the sweep falls through to a full-escrow refund and mints the
     * lost stake (MP-13). Defaults empty so a pre-MP-13 snapshot round-trips
     * safely — an absent entry just means "no recorded last-known stack."
     */
    val lastKnownStacks: Map<String, Long> = emptyMap(),
    val updatedAt: Instant,
)

/**
 * Test fallback. Used by registry / session tests that don't want a
 * real Postgres in the loop. Production wiring goes through
 * [PostgresSessionSnapshotStore] via DI.
 */
class NoOpSessionSnapshotStore : SessionSnapshotStore {
    override suspend fun upsert(snapshot: SessionSnapshot) = Unit
    override suspend fun readByCode(code: String): SessionSnapshot? = null
    override suspend fun deleteByCode(code: String) = Unit
}

package com.dangerfield.cards.server.domain

/**
 * Boot-time recovery for the multiplayer chip economy.
 *
 * V1 rooms live in memory, so a server restart vaporizes every room — any
 * [TableSession] still `open`/`closing` after a restart is therefore
 * abandoned (its room is gone, nobody can rejoin), and the player's
 * debited buy-in is stranded behind the one-active-session double-spend
 * guard. This sweep refunds them: cash each open session out from its
 * room's last durable snapshot and close it.
 *
 * Idempotent and safe to run repeatedly — cash-out is keyed per session
 * and closes the row, so a re-run settles nothing already settled.
 */
interface TableSessionRecoverySweep {
    /** Settle every still-open session from its snapshot; returns the count settled. */
    suspend fun sweepAbandonedSessions(): Int
}

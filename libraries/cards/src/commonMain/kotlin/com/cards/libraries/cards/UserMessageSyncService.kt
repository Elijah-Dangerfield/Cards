package com.dangerfield.cards.libraries.cards

/**
 * Pulls the user's unread server-scheduled messages and hands them to
 * [UserMessageRepository]. Triggered by an app-launch hook on cold-boot
 * and warm-foreground (mirrors [ChipsSyncService]).
 *
 * Sync model is "client tells server what it acked, server returns
 * what's still unread + unexpired." Anything in the local cache that
 * isn't in the response gets deleted regardless of reason (acked
 * through, expired, admin reversed, server purged) — the server's
 * response IS the truth at the sync boundary.
 *
 * Failure modes:
 *  - Network / 401 / 5xx: leaves the local cache untouched. The
 *    user still sees any cached not-yet-shown messages (offline-
 *    first), and the pending ack queue stays for next time.
 *
 * Result-based; exceptions don't escape.
 */
interface UserMessageSyncService {
    suspend fun sync(): Result<Unit>
}

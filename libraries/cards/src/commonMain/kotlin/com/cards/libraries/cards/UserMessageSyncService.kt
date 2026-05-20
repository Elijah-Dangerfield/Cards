package com.dangerfield.cards.libraries.cards

/**
 * Pulls the user's unread server-scheduled dialogs and hands them to
 * [UserMessageRepository]. Triggered by an app-launch hook on cold-boot
 * and warm-foreground (mirrors [ChipsSyncService]).
 *
 * Failure modes:
 *  - Network / 401 / 5xx: leaves the local cache untouched. The next
 *    foreground retries; the user just doesn't see the dialog this
 *    session.
 *
 * Result-based; exceptions don't escape.
 */
interface UserMessageSyncService {
    suspend fun sync(): Result<Unit>
}

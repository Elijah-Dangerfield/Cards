package com.dangerfield.cards.libraries.cards

import kotlinx.coroutines.flow.Flow

/**
 * Local cache + read surface for server-scheduled in-app messages.
 *
 * Backed by a Room table that holds both dialog and inbox kinds in
 * one place; the [InAppMessageManager] gates which dialog (if any)
 * surfaces on each foreground, and [observeInbox] /
 * [observeUnreadInboxCount] power the Notifications screen + the
 * bottom-bar badge respectively.
 *
 * Fetching is owned by [sync] — the repository's reads are the cached
 * state; [sync] pulls fresh and reconciles.
 *
 * Acks are queued locally and shipped in the next [sync]'s POST body.
 * The client never directly POSTs an ack.
 */
interface UserMessageRepository {

    /** Newest-first inbox rows (read + unread, expiry-filtered). */
    fun observeInbox(): Flow<List<UserMessage>>

    /** Unread inbox count — the bottom-bar badge subscribes to this. */
    fun observeUnreadInboxCount(): Flow<Int>

    /**
     * Returns the next unread, unexpired dialog and marks it shown +
     * pending-ack in a single transaction. Returns null if none is
     * eligible. The "1 per foreground" gate calls this once per
     * foreground event.
     */
    suspend fun consumeNextDialog(): UserMessage?

    /**
     * Mark every unread inbox row as shown + pending-ack. Called when
     * the Notifications screen enters Resumed — the badge clears
     * immediately because the count query filters on `shown_at IS NULL`.
     * Returns the number of rows flipped (rarely needed; mostly for tests).
     */
    suspend fun markAllInboxShown(): Int

    /**
     * Replace the local cache with [messages] inside one transaction.
     * Called by [sync] after a successful sync —
     * anything in local but not in [messages] gets deleted; rows that
     * survive the diff keep their local `shown_at` / `acked_pending`
     * flags.
     */
    suspend fun replaceCache(messages: List<UserMessage>)

    /**
     * Ids the next sync should batch up as the `ackedIds` field of
     * the POST body. Cleared on successful reconciliation (those ids
     * either won't come back, or come back unchanged — see [sync]
     * for the reconciliation rule).
     */
    suspend fun pendingAckIds(): List<String>

    /**
     * Pull fresh from `/v1/me/messages/sync`, ship pending acks, replace
     * the local cache with the server's response. Single-flight.
     *
     * Lifecycle is driven by [InAppMessageManager] — it awaits identity
     * + calls this before consuming the next dialog on cold boot / warm
     * foreground. Direct callers should be rare.
     *
     * Failure leaves the local cache untouched (offline-first). The next
     * cycle retries with the same ack queue. Result-based.
     */
    suspend fun sync(): Result<Unit>
}

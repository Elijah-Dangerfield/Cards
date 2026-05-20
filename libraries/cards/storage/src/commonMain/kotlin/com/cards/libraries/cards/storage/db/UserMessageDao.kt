package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Local store for server-scheduled in-app messages. See
 * [UserMessageEntity] for the full state machine + lifecycle notes.
 *
 * The Flow-returning queries power reactive UI: the Notifications
 * screen observes [observeUnreadInbox], the bottom-bar badge observes
 * [observeUnreadInboxCount]. The non-Flow queries are used by the
 * sync service + [InAppMessageManager] dialog gate.
 */
@Dao
interface UserMessageDao : ClearableDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<UserMessageEntity>)

    @Query("SELECT * FROM user_messages")
    suspend fun getAll(): List<UserMessageEntity>

    /**
     * Pending-ack ids the next sync POST will batch up.
     */
    @Query("SELECT id FROM user_messages WHERE acked_pending = 1")
    suspend fun pendingAckIds(): List<String>

    /**
     * Replace the local cache with [messages] inside one transaction so
     * the UI doesn't observe a momentary empty state. Called by the
     * sync service after every successful round-trip with the rule
     * "server response IS the truth at the sync boundary."
     *
     * Preserves the `shown_at` / `acked_pending` flags for any id that
     * survives the diff — those are client-side display state the
     * server doesn't know about.
     */
    @Transaction
    suspend fun replaceCache(messages: List<UserMessageEntity>) {
        val existingById = getAll().associateBy { it.id }
        val merged = messages.map { incoming ->
            val existing = existingById[incoming.id]
            incoming.copy(
                shownAtEpochMs = existing?.shownAtEpochMs,
                ackedPending = existing?.ackedPending ?: false,
            )
        }
        deleteAll()
        upsertAll(merged)
    }

    /**
     * Mark the first eligible dialog as shown — used by the
     * "1 per foreground" gate. Returns the row that was marked, or
     * null if none was eligible.
     */
    @Transaction
    suspend fun consumeNextDialog(nowEpochMs: Long): UserMessageEntity? {
        val head = nextDialog(nowEpochMs) ?: return null
        markShown(listOf(head.id), nowEpochMs)
        return head.copy(shownAtEpochMs = nowEpochMs, ackedPending = true)
    }

    @Query("""
        SELECT * FROM user_messages
        WHERE kind = 'dialog'
          AND shown_at_epoch_ms IS NULL
          AND (expires_at_epoch_ms IS NULL OR expires_at_epoch_ms > :nowEpochMs)
        ORDER BY created_at_epoch_ms ASC
        LIMIT 1
    """)
    suspend fun nextDialog(nowEpochMs: Long): UserMessageEntity?

    /**
     * Mark every inbox row currently visible to the user as shown +
     * pending ack. Called on Notifications screen resume. Idempotent —
     * already-shown rows aren't touched.
     */
    @Query("""
        UPDATE user_messages
        SET shown_at_epoch_ms = :nowEpochMs,
            acked_pending = 1
        WHERE kind = 'inbox'
          AND shown_at_epoch_ms IS NULL
          AND (expires_at_epoch_ms IS NULL OR expires_at_epoch_ms > :nowEpochMs)
    """)
    suspend fun markAllUnreadInboxShown(nowEpochMs: Long): Int

    @Query("""
        UPDATE user_messages
        SET shown_at_epoch_ms = :nowEpochMs,
            acked_pending = 1
        WHERE id IN (:ids)
    """)
    suspend fun markShown(ids: List<String>, nowEpochMs: Long): Int

    /**
     * All inbox messages — read AND unread — newest first, filtered
     * by expiry. Powers the Notifications screen which shows
     * everything until the row is acked + reaped server-side.
     */
    @Query("""
        SELECT * FROM user_messages
        WHERE kind = 'inbox'
          AND (expires_at_epoch_ms IS NULL OR expires_at_epoch_ms > :nowEpochMs)
        ORDER BY created_at_epoch_ms DESC
    """)
    fun observeInbox(nowEpochMs: Long): Flow<List<UserMessageEntity>>

    /**
     * Unread inbox count for the bottom-bar badge. Reactive — the
     * badge clears the moment Notifications opens because that's
     * when [markAllUnreadInboxShown] runs.
     */
    @Query("""
        SELECT COUNT(*) FROM user_messages
        WHERE kind = 'inbox'
          AND shown_at_epoch_ms IS NULL
          AND (expires_at_epoch_ms IS NULL OR expires_at_epoch_ms > :nowEpochMs)
    """)
    fun observeUnreadInboxCount(nowEpochMs: Long): Flow<Int>

    @Query("DELETE FROM user_messages")
    override suspend fun deleteAll()
}

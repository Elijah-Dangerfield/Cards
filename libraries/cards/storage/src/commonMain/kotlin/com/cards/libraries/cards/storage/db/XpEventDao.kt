package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface XpEventDao : ClearableDao {

    @Insert
    suspend fun insertAll(events: List<XpEventEntity>)

    @Query(
        """
        SELECT * FROM xp_events
        WHERE user_id = 'user' AND created_at_epoch_ms >= :sinceEpochMs
        ORDER BY created_at_epoch_ms DESC
        """
    )
    fun observeSince(sinceEpochMs: Long): Flow<List<XpEventEntity>>

    @Query(
        """
        SELECT * FROM xp_events
        WHERE user_id = 'user'
        ORDER BY created_at_epoch_ms DESC
        LIMIT :limit
        """
    )
    fun observeRecent(limit: Int): Flow<List<XpEventEntity>>

    /**
     * The oldest [limit] rows not yet flushed to the server, in apply order.
     *
     * The limit is mandatory: an unbounded read is what let one player's
     * outbox grow to 2,703 rows and put a five-minute request on the server
     * every sync (ENG-45). Callers page until the outbox drains.
     */
    @Query(
        """
        SELECT * FROM xp_events
        WHERE user_id = 'user' AND synced = 0
        ORDER BY created_at_epoch_ms ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getUnsynced(limit: Int): List<XpEventEntity>

    /** Mark rows synced once the server has acked them (by idempotency key). */
    @Query("UPDATE xp_events SET synced = 1 WHERE idempotency_key IN (:keys)")
    suspend fun markSynced(keys: List<String>)

    /**
     * Idempotency keys already present locally, for the re-hydration dedup:
     * server-echoed rows whose key is already held must not be re-inserted
     * (the local ledger has no unique index on the key — Room would duplicate).
     */
    @Query("SELECT idempotency_key FROM xp_events WHERE idempotency_key IN (:keys)")
    suspend fun existingKeys(keys: List<String>): List<String>

    @Query("DELETE FROM xp_events")
    override suspend fun deleteAll()
}

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

    /** Rows not yet flushed to the server, oldest first (apply order). */
    @Query(
        """
        SELECT * FROM xp_events
        WHERE user_id = 'user' AND synced = 0
        ORDER BY created_at_epoch_ms ASC, id ASC
        """
    )
    suspend fun getUnsynced(): List<XpEventEntity>

    /** Mark rows synced once the server has acked them (by idempotency key). */
    @Query("UPDATE xp_events SET synced = 1 WHERE idempotency_key IN (:keys)")
    suspend fun markSynced(keys: List<String>)

    @Query("DELETE FROM xp_events")
    override suspend fun deleteAll()
}

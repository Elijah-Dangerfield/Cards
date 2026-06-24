package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PlayStyleEventDao : ClearableDao {

    @Insert
    suspend fun insertAll(events: List<PlayStyleEventEntity>)

    /** Rows not yet flushed to the server, oldest first (apply order). */
    @Query(
        """
        SELECT * FROM play_style_events
        WHERE user_id = 'user' AND synced = 0
        ORDER BY created_at_epoch_ms ASC, id ASC
        """
    )
    suspend fun getUnsynced(): List<PlayStyleEventEntity>

    /** Mark rows synced once the server has acked them (by idempotency key). */
    @Query("UPDATE play_style_events SET synced = 1 WHERE idempotency_key IN (:keys)")
    suspend fun markSynced(keys: List<String>)

    @Query("DELETE FROM play_style_events")
    override suspend fun deleteAll()
}

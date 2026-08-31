package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PlayStyleEventDao : ClearableDao {

    @Insert
    suspend fun insertAll(events: List<PlayStyleEventEntity>)

    /**
     * The oldest [limit] rows not yet flushed to the server, in apply order.
     * Mandatory limit for the same reason as [XpEventDao.getUnsynced] — an
     * unbounded flush has no ceiling and no exit once it starts timing out.
     */
    @Query(
        """
        SELECT * FROM play_style_events
        WHERE user_id = 'user' AND synced = 0
        ORDER BY created_at_epoch_ms ASC, id ASC
        LIMIT :limit
        """
    )
    suspend fun getUnsynced(limit: Int): List<PlayStyleEventEntity>

    /** Mark rows synced once the server has acked them (by idempotency key). */
    @Query("UPDATE play_style_events SET synced = 1 WHERE idempotency_key IN (:keys)")
    suspend fun markSynced(keys: List<String>)

    @Query("DELETE FROM play_style_events")
    override suspend fun deleteAll()
}

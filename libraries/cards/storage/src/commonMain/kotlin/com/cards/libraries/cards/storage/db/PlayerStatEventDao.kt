package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PlayerStatEventDao : ClearableDao {

    @Insert
    suspend fun insertAll(events: List<PlayerStatEventEntity>)

    /** Rows not yet flushed to the server, oldest first (apply order). */
    @Query(
        """
        SELECT * FROM player_stat_events
        WHERE user_id = 'user' AND synced = 0
        ORDER BY created_at_epoch_ms ASC, id ASC
        """
    )
    suspend fun getUnsynced(): List<PlayerStatEventEntity>

    /** Mark rows synced once the server has acked them (by idempotency key). */
    @Query("UPDATE player_stat_events SET synced = 1 WHERE idempotency_key IN (:keys)")
    suspend fun markSynced(keys: List<String>)

    @Query("DELETE FROM player_stat_events")
    override suspend fun deleteAll()
}

package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface XpEventDao {

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

    @Query("DELETE FROM xp_events")
    suspend fun deleteAll()
}

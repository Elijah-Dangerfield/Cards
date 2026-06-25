package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerStatsDao : ClearableDao {

    @Query("SELECT * FROM player_stats WHERE id = 'user' LIMIT 1")
    fun observe(): Flow<PlayerStatsEntity?>

    @Query("SELECT * FROM player_stats WHERE id = 'user' LIMIT 1")
    suspend fun get(): PlayerStatsEntity?

    /** Overwrite the cached snapshot with the server's latest values. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: PlayerStatsEntity)

    @Query("DELETE FROM player_stats")
    override suspend fun deleteAll()
}

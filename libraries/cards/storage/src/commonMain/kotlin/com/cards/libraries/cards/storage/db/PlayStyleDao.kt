package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayStyleDao : ClearableDao {

    @Query("SELECT * FROM play_style WHERE id = 'user' LIMIT 1")
    fun observe(): Flow<PlayStyleEntity?>

    @Query("SELECT * FROM play_style WHERE id = 'user' LIMIT 1")
    suspend fun get(): PlayStyleEntity?

    /** Overwrite the cached axes with the server's latest computed values. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entity: PlayStyleEntity)

    @Query("DELETE FROM play_style")
    override suspend fun deleteAll()
}

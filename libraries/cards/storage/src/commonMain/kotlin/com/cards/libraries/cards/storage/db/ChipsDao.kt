package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChipsDao {

    @Query("SELECT * FROM chips WHERE id = 'user' LIMIT 1")
    fun observeChips(): Flow<ChipsEntity?>

    @Query("SELECT * FROM chips WHERE id = 'user' LIMIT 1")
    suspend fun getChips(): ChipsEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: ChipsEntity)

    @Query(
        "UPDATE chips SET balance = balance + :delta, updated_at_epoch_ms = :updatedAtEpochMs " +
            "WHERE id = 'user'"
    )
    suspend fun applyDelta(delta: Long, updatedAtEpochMs: Long)

    @Query("DELETE FROM chips")
    suspend fun deleteAll()
}

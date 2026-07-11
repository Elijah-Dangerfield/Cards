package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChipsDao : ClearableDao {

    @Query("SELECT * FROM chips WHERE id = 'user' LIMIT 1")
    fun observeChips(): Flow<ChipsEntity?>

    @Query("SELECT * FROM chips WHERE id = 'user' LIMIT 1")
    suspend fun getChips(): ChipsEntity?

    /** Overwrite (or create) the singleton snapshot row. The row holds only
     *  the last authoritative server balance — optimistic deltas live in the
     *  wallet-events outbox and are folded on read, never blended in here. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChipsEntity)

    @Query("DELETE FROM chips")
    override suspend fun deleteAll()
}

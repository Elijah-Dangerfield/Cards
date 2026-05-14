package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    // ----- earned ledger -----

    @Query("SELECT * FROM achievement_earned")
    fun observeEarned(): Flow<List<AchievementEarnedEntity>>

    @Query("SELECT * FROM achievement_earned")
    suspend fun getEarned(): List<AchievementEarnedEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEarned(entity: AchievementEarnedEntity)

    @Query("DELETE FROM achievement_earned")
    suspend fun deleteAllEarned()

    // ----- counters (per-achievement + custom) -----

    @Query("SELECT * FROM achievement_counter")
    fun observeCounters(): Flow<List<AchievementCounterEntity>>

    @Query("SELECT * FROM achievement_counter")
    suspend fun getCounters(): List<AchievementCounterEntity>

    @Query("SELECT value FROM achievement_counter WHERE key = :key")
    suspend fun getCounter(key: String): Int?

    /**
     * Idempotent +N for a counter. Inserts if missing, accumulates if
     * present. Room's UPSERT pattern with COALESCE.
     */
    @Query(
        """
        INSERT INTO achievement_counter (key, value) VALUES (:key, :delta)
        ON CONFLICT(key) DO UPDATE SET value = value + :delta
        """
    )
    suspend fun incrementCounter(key: String, delta: Int)

    /** Set the counter to an absolute value (used by streak resets). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setCounter(entity: AchievementCounterEntity)

    @Query("DELETE FROM achievement_counter")
    suspend fun deleteAllCounters()
}

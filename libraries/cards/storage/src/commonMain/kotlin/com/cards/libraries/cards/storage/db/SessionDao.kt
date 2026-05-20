package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao : ClearableDao {

    // ========== Observe ==========

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 1")
    fun observeLatestSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    // ========== Get ==========

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestSession(): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveSession(): SessionEntity?

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun getSessionCount(): Int

    // ========== Insert ==========

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    // ========== Update ==========

    @Update
    suspend fun update(session: SessionEntity)

    @Query("DELETE FROM sessions")
    override suspend fun deleteAll()
}

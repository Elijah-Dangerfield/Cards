package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The earned-achievement ledger. Progress counters are NOT stored here anymore —
 * they're derived from the server-authoritative effective counters (server
 * snapshot + unsynced player-stats outbox; see `PlayerStatsRepository`), so this
 * DAO owns only which achievements have been earned (synced to the server).
 */
@Dao
interface AchievementDao : ClearableDao {

    @Query("SELECT * FROM achievement_earned")
    fun observeEarned(): Flow<List<AchievementEarnedEntity>>

    @Query("SELECT * FROM achievement_earned")
    suspend fun getEarned(): List<AchievementEarnedEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEarned(entity: AchievementEarnedEntity)

    /** Earned rows not yet flushed to the server. */
    @Query("SELECT * FROM achievement_earned WHERE synced = 0")
    suspend fun getUnsyncedEarned(): List<AchievementEarnedEntity>

    /** Mark earned rows synced once the server has acked them. */
    @Query("UPDATE achievement_earned SET synced = 1 WHERE achievement_id IN (:ids)")
    suspend fun markEarnedSynced(ids: List<String>)

    @Query("DELETE FROM achievement_earned")
    suspend fun deleteAllEarned()

    override suspend fun deleteAll() = deleteAllEarned()
}

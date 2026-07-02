package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WalletEventDao : ClearableDao {

    /** Pending wallet events to flush to the server, oldest first so the
     *  server applies them in the same order the user generated them. */
    @Query("SELECT * FROM wallet_events ORDER BY applied_at_epoch_ms ASC")
    suspend fun getAll(): List<WalletEventEntity>

    /** Idempotent on the primary key — if the same idempotency key is
     *  inserted twice we keep the existing row. The server's behavior
     *  on a duplicate is the same (AlreadyApplied no-op). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: WalletEventEntity)

    /** Number of events already recorded under [key] (0 or 1, since it's the
     *  primary key). Lets the repository skip a re-applied delta whose event
     *  the IGNORE insert would silently drop — the balance delta isn't
     *  key-aware, so without this check a duplicate key double-counts locally. */
    @Query("SELECT COUNT(*) FROM wallet_events WHERE idempotency_key = :key")
    suspend fun countByKey(key: String): Int

    @Query("DELETE FROM wallet_events WHERE idempotency_key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("DELETE FROM wallet_events")
    override suspend fun deleteAll()
}

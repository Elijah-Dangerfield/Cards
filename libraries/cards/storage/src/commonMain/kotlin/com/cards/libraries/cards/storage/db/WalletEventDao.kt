package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    /** Sum of all pending deltas — the optimistic fold the displayed balance
     *  adds on top of the server snapshot. NULL when the outbox is empty
     *  (SQL SUM semantics), which callers use to tell "no pending events"
     *  apart from "pending events that net to zero". */
    @Query("SELECT SUM(delta) FROM wallet_events")
    fun observePendingDelta(): Flow<Long?>

    /** One-shot form of [observePendingDelta]. */
    @Query("SELECT SUM(delta) FROM wallet_events")
    suspend fun pendingDelta(): Long?

    @Query("DELETE FROM wallet_events WHERE idempotency_key IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("DELETE FROM wallet_events")
    override suspend fun deleteAll()
}

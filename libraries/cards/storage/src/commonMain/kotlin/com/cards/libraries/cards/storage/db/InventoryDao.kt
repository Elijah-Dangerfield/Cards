package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao : ClearableDao {

    @Query("SELECT * FROM inventory ORDER BY purchased_at_epoch_ms DESC")
    fun observeAll(): Flow<List<InventoryEntity>>

    @Query("SELECT * FROM inventory ORDER BY purchased_at_epoch_ms DESC")
    suspend fun getAll(): List<InventoryEntity>

    @Query("SELECT * FROM inventory WHERE product_id = :productId LIMIT 1")
    suspend fun getByProductId(productId: String): InventoryEntity?

    @Query("SELECT * FROM inventory WHERE sync_state = 'Pending'")
    suspend fun getPending(): List<InventoryEntity>

    /** Returns the row id of the new entry, or -1 if a row with this product
     *  id already existed. The duplicate path is the "already owned" signal. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(entity: InventoryEntity): Long

    /** Upsert variant used by `applyServerSnapshot` — REPLACE because the
     *  server-authoritative row supersedes whatever the local row said
     *  (Pending → Confirmed, or refreshed timestamp / cost). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<InventoryEntity>)

    @Query("UPDATE inventory SET sync_state = 'Confirmed' WHERE product_id IN (:productIds)")
    suspend fun markConfirmed(productIds: Collection<String>)

    @Query("DELETE FROM inventory WHERE product_id = :productId")
    suspend fun delete(productId: String)

    /** Used by `applyServerSnapshot` to revoke Confirmed rows the server no
     *  longer agrees the user owns. Pending rows are left alone — they're
     *  in-flight and the next sync resolves them. */
    @Query("DELETE FROM inventory WHERE product_id IN (:productIds) AND sync_state = 'Confirmed'")
    suspend fun deleteConfirmed(productIds: Collection<String>)

    @Query("DELETE FROM inventory")
    override suspend fun deleteAll()
}

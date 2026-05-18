package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EquipmentDao {

    @Query("SELECT * FROM equipment WHERE is_equipped = 1 ORDER BY updated_at_epoch_ms DESC")
    fun observeEquipped(): Flow<List<EquipmentEntity>>

    @Query("SELECT * FROM equipment ORDER BY updated_at_epoch_ms DESC")
    suspend fun getAll(): List<EquipmentEntity>

    @Query("SELECT * FROM equipment WHERE sync_state = 'Pending'")
    suspend fun getPending(): List<EquipmentEntity>

    @Query("SELECT * FROM equipment WHERE product_id = :productId LIMIT 1")
    suspend fun getByProductId(productId: String): EquipmentEntity?

    /** Upsert by primary-key (product_id). REPLACE strategy means a fresh
     *  toggle for the same product overwrites the prior row outright —
     *  intentional: the new intent is always what the user wants now. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EquipmentEntity)

    @Query("UPDATE equipment SET sync_state = 'Synced' WHERE product_id IN (:productIds)")
    suspend fun markSynced(productIds: Collection<String>)

    /** Purge fully-resolved unequipped rows so the table doesn't grow without
     *  bound — once an unequip is synced, the row's no longer carrying any
     *  information (absence == unequipped). */
    @Query("DELETE FROM equipment WHERE is_equipped = 0 AND sync_state = 'Synced'")
    suspend fun purgeSyncedUnequips()

    /** Replace every row with the server-canonical snapshot. Used during
     *  the cold-start fetch when the server's truth might diverge from
     *  the local view (e.g. user equipped from another device). */
    @androidx.room.Transaction
    @Query("DELETE FROM equipment")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EquipmentEntity>)
}

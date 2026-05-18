package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per product the user has touched the equip toggle on. Every
 * row carries both the intent (`is_equipped` boolean) AND a sync state
 * — so an offline unequip can be picked up by the next sync just like
 * an offline equip is.
 *
 * Why we keep `is_equipped = 0` rows around instead of deleting:
 * if the user unequipped a product offline, we still need to send
 * "please unequip on the server" on the next sync. Deleting the row
 * would erase that intent. The sync service purges (or marks
 * Synced) the no-op rows after the server acks.
 *
 * `sync_state` matches [com.dangerfield.cards.libraries.cards.EquipmentSyncState]
 * — stored as the enum name string for the same forward-compat reason
 * the inventory entity uses.
 */
@Entity(tableName = "equipment")
data class EquipmentEntity(
    @PrimaryKey
    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "is_equipped")
    val isEquipped: Boolean,

    @ColumnInfo(name = "sync_state")
    val syncState: String,

    /** Wall-clock at which the user toggled the state. Drives server
     *  last-write-wins reconciliation. */
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
)

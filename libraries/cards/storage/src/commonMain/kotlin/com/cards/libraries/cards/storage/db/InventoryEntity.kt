package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per owned product. Primary key is the product id from the catalog,
 * so re-redeeming the same item is a natural insert-ignore (and the repo
 * surfaces [RedeemResult.AlreadyOwned] without bumping a counter).
 *
 * `sync_state` matches [com.dangerfield.cards.libraries.cards.PurchaseState]
 * — stored as the enum name string (`"Pending"` / `"Confirmed"`) so a future
 * value addition doesn't break migration.
 */
@Entity(tableName = "inventory")
data class InventoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "product_id")
    val productId: String,

    @ColumnInfo(name = "sync_state")
    val syncState: String,

    @ColumnInfo(name = "purchased_at_epoch_ms")
    val purchasedAtEpochMs: Long,

    @ColumnInfo(name = "cost_chips_at_purchase", defaultValue = "0")
    val costChipsAtPurchase: Long,

    /**
     * "purchased" | "earned". Mirrors the server's
     * `inventory.acquisition_source` column. Default 'purchased' keeps
     * pre-V13 rows interpretable as the historical "every row was a
     * purchase" state. Stored as a string (matches `sync_state`) so
     * adding a new value later doesn't require a column migration —
     * just an enum update on the read side.
     */
    @ColumnInfo(name = "acquisition_source", defaultValue = "purchased")
    val acquisitionSource: String = "purchased",
)

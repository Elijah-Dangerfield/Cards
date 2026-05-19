package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per chip-event the client has applied locally but hasn't yet
 * acknowledged with the server's wallet ledger. Lives next to the
 * singleton [ChipsEntity] which holds the optimistic local balance — the
 * delta has already been added to that balance, this row is the
 * outstanding sync receipt.
 *
 * Once the sync round-trip lands a confirmation (Applied or
 * AlreadyApplied or InsufficientChips) the row is deleted. On
 * InsufficientChips the local balance is also reset to the server's
 * authoritative value, since the client's optimistic write was rejected.
 *
 * The primary key is the client-generated idempotency key (typically a
 * UUID v4 string). The server uses the same key as its dedup boundary,
 * so a retry after a flaky upload doesn't double-apply the delta.
 */
@Entity(tableName = "wallet_events")
data class WalletEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "delta")
    val delta: Long,

    /** Free-form short label describing the source. Same shape as the
     *  server-side `reason` column: "starter_grant",
     *  "iap.chip_pack_medium", "achievement.first_full_house", etc. */
    @ColumnInfo(name = "reason")
    val reason: String,

    @ColumnInfo(name = "applied_at_epoch_ms")
    val appliedAtEpochMs: Long,
)

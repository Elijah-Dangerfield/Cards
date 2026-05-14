package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton chips balance — the player's spending power for the shop, future
 * multiplayer buy-ins, and any other chip-economy surfaces.
 *
 * Per docs/decisions.md (2026-05-14): chips are an independent axis from XP
 * and rank, *sacred* once we ship multiplayer. In V1 (bots only) the balance
 * never moves — the seat auto-rebuys mid-hand from a separate practice pool.
 * Persisting it now means home + shop both observe the same source of truth
 * and Phase 3 / future shop purchases plug in without a rewrite.
 */
@Entity(tableName = "chips")
data class ChipsEntity(
    @PrimaryKey
    val id: String = "user", // Singleton — matches UserEntity convention.

    @ColumnInfo(name = "balance", defaultValue = "0")
    val balance: Long = 0,

    @ColumnInfo(name = "updated_at_epoch_ms", defaultValue = "0")
    val updatedAtEpochMs: Long = 0,
)

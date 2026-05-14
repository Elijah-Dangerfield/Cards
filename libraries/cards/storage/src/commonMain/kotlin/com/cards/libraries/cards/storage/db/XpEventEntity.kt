package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Append-only XP ledger. Each hand produces one row per non-zero XP source
 * (base, investment, showdown bonus, hand-strength bonus).
 *
 * Mirrors the server-side `xp_events` table from docs/decisions.md so the
 * Phase 3 migration is a straight backfill.
 *
 * Source values are stored as strings (not enum ordinals) so adding new
 * sources later doesn't shift existing rows.
 */
@Entity(tableName = "xp_events")
data class XpEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "user_id")
    val userId: String = "user",

    @ColumnInfo(name = "delta_xp")
    val deltaXp: Int,

    @ColumnInfo(name = "source")
    val source: String, // BASE | INVESTMENT | SHOWDOWN | HAND_STRENGTH

    @ColumnInfo(name = "mode")
    val mode: String, // BOTS | MULTIPLAYER

    @ColumnInfo(name = "hand_id")
    val handId: String?,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton progression row — XP and lifetime hand counters.
 *
 * Mirrors the eventual server `progression` table so Phase 3 (auth/server)
 * can backfill these values with a one-shot migration. Until then, this is
 * the local source of truth for the bot-only V1.
 *
 * Decoupled from win/lose per docs/decisions.md (2026-05-14): XP scales with
 * engagement, never with outcome.
 */
@Entity(tableName = "progression")
data class ProgressionEntity(
    @PrimaryKey
    val id: String = "user", // Singleton — matches UserEntity convention

    @ColumnInfo(name = "total_xp", defaultValue = "0")
    val totalXp: Long = 0,

    @ColumnInfo(name = "hands_played", defaultValue = "0")
    val handsPlayed: Long = 0,

    @ColumnInfo(name = "hands_won", defaultValue = "0")
    val handsWon: Long = 0,

    @ColumnInfo(name = "hands_folded", defaultValue = "0")
    val handsFolded: Long = 0,

    @ColumnInfo(name = "hands_lost_at_showdown", defaultValue = "0")
    val handsLostAtShowdown: Long = 0,

    @ColumnInfo(name = "bot_hands_played", defaultValue = "0")
    val botHandsPlayed: Long = 0,

    @ColumnInfo(name = "updated_at_epoch_ms", defaultValue = "0")
    val updatedAtEpochMs: Long = 0,
)

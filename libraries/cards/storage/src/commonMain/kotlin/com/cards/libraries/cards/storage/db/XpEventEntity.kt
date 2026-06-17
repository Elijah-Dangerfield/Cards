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

    /**
     * Stable client-generated key for server idempotency (a UUID per row,
     * set by [ProgressionRepositoryImpl]). The local PK stays the
     * autoincrement [id]; this is the dedup boundary on the *server's*
     * `xp_events` table so a retried sync can't double-count. Defaults empty
     * for test rows that never sync.
     */
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String = "",

    /**
     * False until this row has been flushed to the server (`POST
     * /v1/me/progression/sync`). The pending-ledger flag — mirrors the
     * wallet's separate `wallet_events` table, kept inline here since the
     * XP ledger doubles as both history feed and sync queue.
     */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    @ColumnInfo(name = "delta_xp")
    val deltaXp: Int,

    @ColumnInfo(name = "source")
    val source: String, // BASE | INVESTMENT | SHOWDOWN | HAND_STRENGTH

    @ColumnInfo(name = "mode")
    val mode: String, // BOTS | MULTIPLAYER

    @ColumnInfo(name = "hand_id")
    val handId: String?,

    /**
     * Free-text label for the source. Used to disambiguate achievement events
     * — the recent-XP feed shows "Achievement unlocked · {description}" so
     * the user can tell which one popped instead of an opaque "unlocked".
     */
    @ColumnInfo(name = "description")
    val description: String? = null,

    /**
     * True when an XP boost doubled this award. Round-trips through progression
     * sync (`xp_events.was_boosted`, server V58) so the recent-XP feed's boosted
     * tag survives a reinstall / account switch, not just the local session.
     */
    @ColumnInfo(name = "was_boosted")
    val wasBoosted: Boolean = false,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

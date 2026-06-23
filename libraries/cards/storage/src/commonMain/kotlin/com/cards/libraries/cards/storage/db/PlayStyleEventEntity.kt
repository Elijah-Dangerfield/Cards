package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Outbox of per-hand play-style contributions. One row per finished hand,
 * holding the raw signals the server folds into the user's aggregate. Mirrors
 * the server-side `play_style_events` table (V69) so a flush is a straight
 * forward of these rows.
 *
 * Booleans are stored as-is; the server sums them. The local PK is the
 * autoincrement [id]; [idempotencyKey] is the dedup boundary on the *server*
 * so a retried/reinstalled flush can't double-count a hand.
 */
@Entity(tableName = "play_style_events")
data class PlayStyleEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "user_id")
    val userId: String = "user",

    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String = "",

    /** False until flushed to `POST /v1/me/play-style/sync`. */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    @ColumnInfo(name = "mode")
    val mode: String, // BOTS | MULTIPLAYER

    @ColumnInfo(name = "in_blind")
    val inBlind: Boolean,

    @ColumnInfo(name = "vpip")
    val vpip: Boolean,

    @ColumnInfo(name = "pfr")
    val pfr: Boolean,

    @ColumnInfo(name = "preflop_fold")
    val preflopFold: Boolean,

    @ColumnInfo(name = "aggressive_action_count")
    val aggressiveActionCount: Int,

    @ColumnInfo(name = "call_action_count")
    val callActionCount: Int,

    @ColumnInfo(name = "went_to_showdown")
    val wentToShowdown: Boolean,

    @ColumnInfo(name = "showdown_bluff")
    val showdownBluff: Boolean,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

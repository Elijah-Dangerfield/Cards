package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Outbox of per-hand player-stat contributions. One row per finished hand,
 * holding the signals the server accumulates into the user's aggregate. A flush
 * is a straight forward of these rows to `POST /v1/me/player-stats/sync`.
 *
 * The local PK is the autoincrement [id]; [idempotencyKey] is the dedup
 * boundary on the *server* so a retried or reinstalled flush can't double-count
 * a hand. [noBustStreak] is the client-computed streak length *after* this hand
 * — streaks are order-dependent, so the client carries the snapshot and the
 * server folds latest-current + running-max-best.
 */
@Entity(tableName = "player_stat_events")
data class PlayerStatEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "user_id")
    val userId: String = "user",

    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String = "",

    /** False until flushed to `POST /v1/me/player-stats/sync`. */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false,

    @ColumnInfo(name = "mode")
    val mode: String, // BOTS | MULTIPLAYER

    @ColumnInfo(name = "won")
    val won: Boolean,

    @ColumnInfo(name = "folded")
    val folded: Boolean,

    @ColumnInfo(name = "lost_at_showdown")
    val lostAtShowdown: Boolean,

    @ColumnInfo(name = "vs_bot")
    val vsBot: Boolean,

    /** Bot beaten this hand when [won] && [vsBot]; null otherwise. */
    @ColumnInfo(name = "beaten_bot_id")
    val beatenBotId: String?,

    @ColumnInfo(name = "no_bust_streak")
    val noBustStreak: Long,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

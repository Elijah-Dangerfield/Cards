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

    // Enriched raw facts (PROG-1) — the complete hand record the server folds
    // into every achievement counter.
    @ColumnInfo(name = "busted", defaultValue = "0")
    val busted: Boolean = false,

    @ColumnInfo(name = "start_stack", defaultValue = "0")
    val startStack: Long = 0,

    @ColumnInfo(name = "end_stack", defaultValue = "0")
    val endStack: Long = 0,

    @ColumnInfo(name = "big_blind", defaultValue = "0")
    val bigBlind: Long = 0,

    @ColumnInfo(name = "pot_total", defaultValue = "0")
    val potTotal: Long = 0,

    @ColumnInfo(name = "was_all_in", defaultValue = "0")
    val wasAllIn: Boolean = false,

    @ColumnInfo(name = "won_by_fold", defaultValue = "0")
    val wonByFold: Boolean = false,

    @ColumnInfo(name = "busts_dealt", defaultValue = "0")
    val bustsDealt: Int = 0,

    @ColumnInfo(name = "folded_would_have_lost", defaultValue = "0")
    val foldedWouldHaveLost: Boolean = false,

    @ColumnInfo(name = "hand_strength_shown")
    val handStrengthShown: String? = null,

    @ColumnInfo(name = "bot_difficulty")
    val botDifficulty: String? = null,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,
)

package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton cache of the user's own server-authoritative player stats. The
 * server is the source of truth (it accumulates the per-hand counters, folds
 * the no-bust streak, and sums the per-bot wins); this row holds the last
 * synced snapshot so Stats renders instantly and offline.
 *
 * Written only by [PlayerStatsRepositoryImpl] on sync. The per-bot wins map is
 * not modelled relationally here — a flat JSON string keeps the cache a single
 * upsert and the read a single row, matching how the stats screen consumes it.
 */
@Entity(tableName = "player_stats")
data class PlayerStatsEntity(
    @PrimaryKey
    val id: String = "user",

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

    @ColumnInfo(name = "current_no_bust_streak", defaultValue = "0")
    val currentNoBustStreak: Long = 0,

    @ColumnInfo(name = "best_no_bust_streak", defaultValue = "0")
    val bestNoBustStreak: Long = 0,

    /** Per-bot wins as a JSON object (`{"bot_id": count}`). Empty `{}` default. */
    @ColumnInfo(name = "per_bot_wins_json", defaultValue = "{}")
    val perBotWinsJson: String = "{}",

    /** Full achievement-counter projection as a JSON object (`{"counter": value}`). */
    @ColumnInfo(name = "achievement_counters_json", defaultValue = "{}")
    val achievementCountersJson: String = "{}",

    @ColumnInfo(name = "updated_at_epoch_ms", defaultValue = "0")
    val updatedAtEpochMs: Long = 0,
)

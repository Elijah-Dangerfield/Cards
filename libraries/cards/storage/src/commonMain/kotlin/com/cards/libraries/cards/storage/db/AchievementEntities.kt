package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Each row records that an achievement was earned + when. Per-user; the
 * `achievement_id` is the [AchievementId.name] of the earned achievement.
 *
 * Mirrors the eventual server-side ledger so the Phase 3 sync is a clean
 * import on account claim (same approach as `xp_events`).
 */
@Entity(tableName = "achievement_earned")
data class AchievementEarnedEntity(
    @PrimaryKey
    @ColumnInfo(name = "achievement_id")
    val achievementId: String,

    @ColumnInfo(name = "earned_at_epoch_ms")
    val earnedAtEpochMs: Long,
)

/**
 * Generic key → integer counter. Used for both per-achievement progress
 * (key = [AchievementId.name]) and custom cross-hand counters (key =
 * something like `no_bust_streak`, `wins_vs_bot_Jane`).
 */
@Entity(tableName = "achievement_counter")
data class AchievementCounterEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value", defaultValue = "0")
    val value: Int,
)

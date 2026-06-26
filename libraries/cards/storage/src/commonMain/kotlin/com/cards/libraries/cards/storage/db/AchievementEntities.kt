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

    /**
     * False until this earned row has been flushed to the server
     * (`POST /v1/me/achievements/sync`). Rows pulled down from the server
     * (earned on another device) are inserted with `synced = true`.
     */
    @ColumnInfo(name = "synced")
    val synced: Boolean = false,
)

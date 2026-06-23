package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Singleton cache of the user's own server-computed play-style axes. The
 * server is authoritative (it sums the per-hand counters and computes the
 * four axes); this row just holds the last synced values so Profile / Stats
 * render instantly and offline.
 *
 * Written only by [PlayStyleRepositoryImpl] on sync — never derived locally,
 * so the count → axis formula stays server-side and tunable.
 */
@Entity(tableName = "play_style")
data class PlayStyleEntity(
    @PrimaryKey
    val id: String = "user", // Singleton — matches ProgressionEntity convention

    @ColumnInfo(name = "tight", defaultValue = "0")
    val tight: Double = 0.0,

    @ColumnInfo(name = "aggro", defaultValue = "0")
    val aggro: Double = 0.0,

    @ColumnInfo(name = "bluff", defaultValue = "0")
    val bluff: Double = 0.0,

    @ColumnInfo(name = "patient", defaultValue = "0")
    val patient: Double = 0.0,

    /** Hands the axes were computed from; the UI gates display on this. */
    @ColumnInfo(name = "sample_size", defaultValue = "0")
    val sampleSize: Long = 0,

    @ColumnInfo(name = "updated_at_epoch_ms", defaultValue = "0")
    val updatedAtEpochMs: Long = 0,
)

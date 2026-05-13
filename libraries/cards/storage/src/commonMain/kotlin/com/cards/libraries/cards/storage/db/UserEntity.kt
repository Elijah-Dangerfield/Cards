package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User entity - singleton, one per install.
 */
@Entity(tableName = "user")
data class UserEntity(
    @PrimaryKey
    val id: String = "user", // Singleton

    val name: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long, // epoch millis

    @ColumnInfo(name = "last_session_at")
    val lastSessionAt: Long?, // epoch millis

    // Flags
    @ColumnInfo(name = "has_completed_onboarding")
    val hasCompletedOnboarding: Boolean = false,

    // Stats
    @ColumnInfo(name = "sessions_count")
    val sessionsCount: Int = 0,

    @ColumnInfo(name = "app_open_count")
    val appOpenCount: Int = 0,

    @ColumnInfo(name = "shake_count")
    val shakeCount: Int = 0,
)

package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Instant

/**
 * Represents a user session - a contiguous period of app usage.
 *
 * A new session is created when:
 * - App is opened fresh (no previous session)
 * - App is foregrounded after 10+ minutes in background
 * - App was force-killed and reopened
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,

    /** When this session started */
    val startedAt: Instant,

    /** When this session ended (null if still active) */
    val endedAt: Instant? = null,

    /** ID of the previous session, for detecting session rollover */
    val previousSessionId: String? = null,
)

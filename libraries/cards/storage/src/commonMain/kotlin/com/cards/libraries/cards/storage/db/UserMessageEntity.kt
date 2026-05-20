package com.dangerfield.cards.libraries.cards.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local mirror of one server-scheduled in-app message.
 *
 * The server is the source of truth — this table is a cache + a queue
 * for pending acks. On each foreground sync:
 *   - `acked_pending = true` rows are sent up as the sync POST's
 *     `ackedIds` batch.
 *   - The server's response replaces our local set: anything not in
 *     the response gets deleted regardless of why (acked through,
 *     expired, server-side purged, admin reversed). The next response
 *     is the truth at the sync boundary.
 *
 * `shown_at` is set the moment the user sees the message (dialog
 * displayed OR inbox row visible on the Notifications screen). It
 * gates re-display — a row with `shown_at` set is never shown again,
 * regardless of whether the ack POST has succeeded yet. Together with
 * `acked_pending` this gives us:
 *
 *   - shown_at = null, acked_pending = false → eligible for display
 *   - shown_at = set,  acked_pending = true  → already shown, ack queued for next sync
 *   - shown_at = set,  acked_pending = false → impossible / never set
 *
 * `expires_at_epoch_ms` mirrors the server's expiry. Client-side
 * display filters by `expires_at_epoch_ms IS NULL OR expires_at_epoch_ms > now`
 * so a stale notice never surfaces even if the sync is lagging.
 *
 * `kind` is `"dialog"` or `"inbox"`. Stored as TEXT so a future
 * surface can be added without a schema migration.
 */
@Entity(tableName = "user_messages")
data class UserMessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "emoji")
    val emoji: String?,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "deep_link")
    val deepLink: String?,

    @ColumnInfo(name = "created_at_epoch_ms")
    val createdAtEpochMs: Long,

    @ColumnInfo(name = "expires_at_epoch_ms")
    val expiresAtEpochMs: Long?,

    @ColumnInfo(name = "shown_at_epoch_ms")
    val shownAtEpochMs: Long?,

    @ColumnInfo(name = "acked_pending")
    val ackedPending: Boolean,
)

package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire formats for the player-report endpoint (MOD-1).
 *
 * `reportedUserId` is the Supabase `auth.users` UUID as a string — the same id
 * the room membership surfaces to the client. `roomCode` and `reason` are
 * optional context: the report is room-scoped today but the wire doesn't force
 * it, and V1 has no reason picker.
 */
@Serializable
data class PlayerReportBody(
    val reportedUserId: String,
    val roomCode: String? = null,
    val reason: String? = null,
)

/**
 * Result of filing a report. `status` is always `received` on success. Shaped
 * like the sibling [FriendRequestResult] action ack (no `schemaVersion`): the
 * server encodes with `encodeDefaults = true`, so an extra defaulted field
 * would go on the wire and the debug client's strict JSON (unknown keys throw)
 * would reject the response even though the report was recorded.
 */
@Serializable
data class PlayerReportResult(
    val status: String,
)

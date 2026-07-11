package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for the server-authoritative progression (XP) endpoints.
 *
 * `GET /v1/me/progression` returns [ProgressionResponse]. `POST
 * /v1/me/progression/sync` accepts [ProgressionSyncRequest] (a batch of
 * locally-applied XP awards the client wants to flush) and returns
 * [ProgressionSyncResponse] with the post-sync authoritative `totalXp` plus
 * per-event outcomes.
 *
 * Mirrors the wallet sync model: "client tells the server what already
 * happened locally, server reconciles." The idempotency key is the dedup
 * boundary — replaying it is a no-op ([XpEventOutcomeDto.AlreadyApplied]),
 * so a flaky network or a reinstall re-flush can't double-count XP. `level`
 * is never on the wire — the client derives it from `totalXp`.
 */
@Serializable
data class ProgressionResponse(
    val totalXp: Long,
    /** True only on the response that lazy-created the row. No consumer today. */
    val progressionCreated: Boolean = false,
)

@Serializable
data class ProgressionSyncRequest(
    val events: List<XpEventDto> = emptyList(),
)

@Serializable
data class XpEventDto(
    /** Client-derived stable key. `hand_<handId>` or `achievement_<id>`. */
    val idempotencyKey: String,
    /** Non-negative; server clamps to a sanity cap before applying. */
    val deltaXp: Long,
    /** Short source tag, e.g. "hand" | "achievement". */
    val source: String,
    /** "BOTS" | "MULTIPLAYER". */
    val mode: String,
    /** Originating hand for hand awards; null for achievement awards. */
    val handId: String? = null,
    /** True when an XP boost doubled this award. Defaulted for older clients. */
    val wasBoosted: Boolean = false,
)

@Serializable
data class ProgressionSyncResponse(
    val schemaVersion: Int = 1,
    /** Post-sync authoritative total, after every event ran. */
    val totalXp: Long,
    val results: List<XpEventResultDto>,
    val progressionCreated: Boolean = false,
    /**
     * Recent server-stored ledger rows, newest first, for re-hydrating a
     * client whose local `xp_events` were wiped (account switch / reinstall).
     * Populated only on a pure-hydrate sync (the request carried no events) so
     * steady-state syncs that already flushed local rows don't re-ship them.
     * The client inserts any key it doesn't already hold, marked synced.
     */
    val recentEvents: List<XpEventSnapshotDto> = emptyList(),
    /**
     * Post-mint wallet balance when this sync crossed a rewarded level and
     * the server credited its chips (ENG-9). Null when nothing was minted.
     * The client re-pulls its wallet on non-null so the reward shows up
     * immediately rather than at the next sync trigger (PROG-12).
     */
    val walletBalance: Long? = null,
)

/**
 * A server-authoritative ledger row echoed back for client re-hydration.
 * Distinct from [XpEventDto] (the client→server flush shape): this carries the
 * server's `appliedAt` as epoch millis so the re-hydrated row sorts into the
 * feed at its original time.
 */
@Serializable
data class XpEventSnapshotDto(
    val idempotencyKey: String,
    val deltaXp: Long,
    val source: String,
    val mode: String,
    val handId: String? = null,
    val wasBoosted: Boolean = false,
    val appliedAtEpochMs: Long,
)

@Serializable
data class XpEventResultDto(
    val idempotencyKey: String,
    val outcome: XpEventOutcomeDto,
    /** Authoritative total immediately after this event was applied. */
    val totalXp: Long,
)

/**
 * Per-event sync outcomes. Additive — clients fall back to [Applied] (most
 * permissive) on an unknown enum value, mirroring wallet/inventory.
 */
@Serializable
enum class XpEventOutcomeDto {
    /** Event committed for the first time. */
    Applied,

    /** Key was already on the server; this call is a no-op replay. */
    AlreadyApplied,
}

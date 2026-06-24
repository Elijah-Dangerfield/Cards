package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for the server-authoritative play-style endpoints.
 *
 * `GET /v1/me/play-style` and `GET /v1/users/{userId}/play-style` return
 * [PlayStyleResponse] (the four computed axes + sample size). `POST
 * /v1/me/play-style/sync` accepts [PlayStyleSyncRequest] (a batch of per-hand
 * contributions the client wants to flush) and returns [PlayStyleSyncResponse]
 * with the post-sync axes plus per-event outcomes.
 *
 * Mirrors the progression sync model: "client tells the server what happened
 * locally, server reconciles." The idempotency key is the dedup boundary —
 * replaying it is a no-op ([PlayStyleEventOutcomeDto.AlreadyApplied]), so a
 * flaky network or reinstall re-flush can't double-count a hand.
 */
@Serializable
data class PlayStyleAxesDto(
    val tight: Double,
    val aggro: Double,
    val bluff: Double,
    val patient: Double,
    /** Hands the axes were computed from; the client gates display on this. */
    val sampleSize: Long,
)

@Serializable
data class PlayStyleResponse(
    val schemaVersion: Int = 1,
    val axes: PlayStyleAxesDto,
)

@Serializable
data class PlayStyleSyncRequest(
    val events: List<PlayStyleHandDto> = emptyList(),
)

/** One finished hand's play-style signals, flushed client → server. */
@Serializable
data class PlayStyleHandDto(
    /** Client-derived stable key, e.g. `style_<handId>`. */
    val idempotencyKey: String,
    /** "BOTS" | "MULTIPLAYER" — tagged for future weighting/filtering. */
    val mode: String,
    /** True when the player only posted a blind (excluded from VPIP denominators). */
    val inBlind: Boolean,
    /** Voluntarily put money in preflop (call or raise, not a blind). */
    val vpip: Boolean,
    /** Raised preflop. */
    val pfr: Boolean,
    /** Folded preflop. */
    val preflopFold: Boolean,
    /** Bets + raises across the whole hand. */
    val aggressiveActionCount: Int,
    /** Calls across the whole hand. */
    val callActionCount: Int,
    val wentToShowdown: Boolean,
    /** Bet/raised into a showdown holding a weak made hand and lost. */
    val showdownBluff: Boolean,
)

@Serializable
data class PlayStyleSyncResponse(
    val schemaVersion: Int = 1,
    /** Post-sync axes, after every event in the batch ran. */
    val axes: PlayStyleAxesDto,
    val results: List<PlayStyleEventResultDto>,
)

@Serializable
data class PlayStyleEventResultDto(
    val idempotencyKey: String,
    val outcome: PlayStyleEventOutcomeDto,
)

/**
 * Per-event sync outcomes. Additive — clients fall back to [Applied] (most
 * permissive) on an unknown enum value, mirroring progression/wallet.
 */
@Serializable
enum class PlayStyleEventOutcomeDto {
    /** Hand committed for the first time. */
    Applied,

    /** Key was already on the server; this call is a no-op replay. */
    AlreadyApplied,
}

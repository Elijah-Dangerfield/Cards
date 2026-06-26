package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for the player-stats endpoints. Mirrors the server's contract in
 * `apps/server/src/.../routes/PlayerStatsDto.kt`.
 *
 * `GET /v1/me/player-stats` returns [PlayerStatsResponseDto]. `POST
 * /v1/me/player-stats/sync` accepts [PlayerStatsSyncRequestDto] (per-hand
 * contributions to flush) and returns [PlayerStatsSyncResponseDto] with the
 * post-sync snapshot + per-event outcomes.
 */
@Serializable
internal data class PlayerStatsDto(
    val handsPlayed: Long,
    val handsWon: Long,
    val handsFolded: Long,
    val handsLostAtShowdown: Long,
    val botHandsPlayed: Long,
    val currentNoBustStreak: Long,
    val bestNoBustStreak: Long,
    val perBotWins: Map<String, Long> = emptyMap(),
    /** Full achievement-counter projection (`name -> value`); progress bars read this. */
    val achievementCounters: Map<String, Long> = emptyMap(),
)

@Serializable
internal data class PlayerStatsResponseDto(
    val schemaVersion: Int = 1,
    val stats: PlayerStatsDto,
)

@Serializable
internal data class PlayerStatsSyncRequestDto(
    val events: List<PlayerStatHandDto>,
)

@Serializable
internal data class PlayerStatHandDto(
    val idempotencyKey: String,
    val mode: String,
    val won: Boolean,
    val folded: Boolean,
    val lostAtShowdown: Boolean,
    val vsBot: Boolean,
    val beatenBotId: String? = null,
    val noBustStreak: Long,
    // Enriched raw facts (PROG-1) — the server folds these into every counter.
    val busted: Boolean? = null,
    val botDifficulty: String? = null,
    val startStack: Long = 0,
    val endStack: Long = 0,
    val bigBlind: Long = 0,
    val potTotal: Long = 0,
    val wasAllIn: Boolean = false,
    val wonByFold: Boolean = false,
    val bustsDealt: Int = 0,
    val foldedWouldHaveLost: Boolean = false,
    val handStrengthShown: String? = null,
)

@Serializable
internal data class PlayerStatsSyncResponseDto(
    val schemaVersion: Int = 1,
    val stats: PlayerStatsDto,
    val results: List<PlayerStatEventResultDto> = emptyList(),
)

@Serializable
internal data class PlayerStatEventResultDto(
    val idempotencyKey: String,
    val outcome: PlayerStatEventOutcomeDto = PlayerStatEventOutcomeDto.Unknown,
)

/**
 * Per-event sync outcomes. [Unknown] is the release-safe fallback for an enum
 * value the client doesn't know yet (mirrors the play-style DTO).
 */
@Serializable
internal enum class PlayerStatEventOutcomeDto {
    @SerialName("Applied") Applied,
    @SerialName("AlreadyApplied") AlreadyApplied,
    @SerialName("Unknown") Unknown,
}

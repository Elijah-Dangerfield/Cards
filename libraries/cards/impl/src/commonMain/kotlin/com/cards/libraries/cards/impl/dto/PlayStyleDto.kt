package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for the play-style endpoints. Mirrors the server's contract in
 * `apps/server/src/.../routes/PlayStyleDto.kt`.
 *
 * `GET /v1/me/play-style` and `GET /v1/users/{userId}/play-style` return
 * [PlayStyleResponseDto]. `POST /v1/me/play-style/sync` accepts
 * [PlayStyleSyncRequestDto] (per-hand contributions to flush) and returns
 * [PlayStyleSyncResponseDto] with the post-sync axes.
 */
@Serializable
internal data class PlayStyleAxesDto(
    val tight: Double,
    val aggro: Double,
    val bluff: Double,
    val patient: Double,
    val sampleSize: Long,
)

@Serializable
internal data class PlayStyleResponseDto(
    val schemaVersion: Int = 1,
    val axes: PlayStyleAxesDto,
)

@Serializable
internal data class PlayStyleSyncRequestDto(
    val events: List<PlayStyleHandDto>,
)

@Serializable
internal data class PlayStyleHandDto(
    val idempotencyKey: String,
    val mode: String,
    val inBlind: Boolean,
    val vpip: Boolean,
    val pfr: Boolean,
    val preflopFold: Boolean,
    val aggressiveActionCount: Int,
    val callActionCount: Int,
    val wentToShowdown: Boolean,
    val showdownBluff: Boolean,
)

@Serializable
internal data class PlayStyleSyncResponseDto(
    val schemaVersion: Int = 1,
    val axes: PlayStyleAxesDto,
    val results: List<PlayStyleEventResultDto> = emptyList(),
)

@Serializable
internal data class PlayStyleEventResultDto(
    val idempotencyKey: String,
    val outcome: PlayStyleEventOutcomeDto = PlayStyleEventOutcomeDto.Unknown,
)

/**
 * Per-event sync outcomes. [Unknown] is the release-safe fallback for an enum
 * value the client doesn't know yet (mirrors the progression DTO).
 */
@Serializable
internal enum class PlayStyleEventOutcomeDto {
    @SerialName("Applied") Applied,
    @SerialName("AlreadyApplied") AlreadyApplied,
    @SerialName("Unknown") Unknown,
}

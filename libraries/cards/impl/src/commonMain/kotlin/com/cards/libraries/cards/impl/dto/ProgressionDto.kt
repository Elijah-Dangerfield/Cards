package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format for `GET /v1/me/progression` and `POST /v1/me/progression/sync`.
 * Mirrors the server's contract in
 * `apps/server/src/.../routes/ProgressionDto.kt`.
 *
 * Same "client tells the server what already happened locally, server
 * reconciles" model as the wallet. `level` is never on the wire — the client
 * derives it from `totalXp`.
 */
@Serializable
internal data class ProgressionResponseDto(
    val totalXp: Long,
    val progressionCreated: Boolean = false,
)

@Serializable
internal data class ProgressionSyncRequestDto(
    val events: List<XpEventDto>,
)

@Serializable
internal data class XpEventDto(
    val idempotencyKey: String,
    val deltaXp: Long,
    val source: String,
    val mode: String,
    val handId: String? = null,
    val wasBoosted: Boolean = false,
)

@Serializable
internal data class ProgressionSyncResponseDto(
    val schemaVersion: Int = 1,
    val totalXp: Long,
    val results: List<XpEventResultDto> = emptyList(),
    val progressionCreated: Boolean = false,
    /**
     * Recent server ledger rows, newest first. Present only on a pure-hydrate
     * sync (no events sent) — the client inserts any key it doesn't already
     * hold (marked synced) to repopulate the recent-XP feed after an account
     * switch / reinstall wiped the local ledger.
     */
    val recentEvents: List<XpEventSnapshotDto> = emptyList(),
)

@Serializable
internal data class XpEventSnapshotDto(
    val idempotencyKey: String,
    val deltaXp: Long,
    val source: String,
    val mode: String,
    val handId: String? = null,
    val wasBoosted: Boolean = false,
    val appliedAtEpochMs: Long,
)

@Serializable
internal data class XpEventResultDto(
    val idempotencyKey: String,
    val outcome: XpEventOutcomeDto = XpEventOutcomeDto.Unknown,
    val totalXp: Long,
)

/**
 * Per-event sync outcomes. [Unknown] is the release-safe fallback for an enum
 * value the client doesn't know yet (mirrors the wallet DTO).
 */
@Serializable
internal enum class XpEventOutcomeDto {
    @SerialName("Applied") Applied,
    @SerialName("AlreadyApplied") AlreadyApplied,
    @SerialName("Unknown") Unknown,
}

package com.dangerfield.cards.libraries.cards.impl.dto

import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /v1/me/achievements/sync`. Mirrors the server's
 * contract in `apps/server/src/.../routes/AchievementsDto.kt`.
 *
 * The client posts its newly-earned ids; the server stores them idempotently
 * and returns the full authoritative earned set so the client can reconcile
 * (absorb anything earned on another device).
 */
@Serializable
internal data class AchievementsSyncRequestDto(
    val earned: List<EarnedAchievementDto>,
)

@Serializable
internal data class EarnedAchievementDto(
    val achievementId: String,
    val earnedAtEpochMs: Long,
)

@Serializable
internal data class AchievementsSyncResponseDto(
    val schemaVersion: Int = 1,
    val earned: List<EarnedAchievementDto> = emptyList(),
)

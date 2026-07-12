package com.dangerfield.cards.server.routes

import kotlinx.serialization.Serializable

/**
 * Wire format for `POST /v1/me/achievements/sync`.
 *
 * Same "client tells the server what it earned locally, server reconciles"
 * model as the wallet/progression syncs. The client posts its newly-earned
 * ids; the server records them idempotently and returns the **full
 * authoritative earned set** so the client can absorb anything earned on
 * another device.
 */
@Serializable
data class AchievementsSyncRequest(
    val earned: List<EarnedAchievementDto> = emptyList(),
)

@Serializable
data class EarnedAchievementDto(
    /** The client's `AchievementId.name`. */
    val achievementId: String,
    val earnedAtEpochMs: Long,
)

@Serializable
data class AchievementsSyncResponse(
    val schemaVersion: Int = 1,
    /** The user's full authoritative earned set after this sync. */
    val earned: List<EarnedAchievementDto>,
    /**
     * Post-mint wallet balance when this sync recorded an achievement whose
     * chips the server credits (ENG-9). Null when nothing was minted. The
     * client re-pulls its wallet on non-null so the reward shows up
     * immediately rather than at the next sync trigger (PROG-12).
     */
    val walletBalance: Long? = null,
)

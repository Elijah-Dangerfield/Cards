package com.dangerfield.cards.server.domain

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * One earned achievement for a user. `achievementId` is the client's
 * `AchievementId.name`; the server treats it as an opaque string (the
 * catalog of definitions lives in the client).
 */
@OptIn(ExperimentalTime::class)
data class EarnedAchievement(
    val achievementId: String,
    val earnedAt: Instant,
)

/**
 * Server-authoritative persistence of the *earned set* (Model 2). The client
 * evaluates criteria offline and flushes newly-earned ids here; the server
 * stores them idempotently and returns the authoritative set so the client
 * can reconcile (cross-device / reinstall). Criteria + progress counters are
 * NOT server-side.
 *
 * Distinct from [InventoryRepository.recordEarnedGrant] (the cosmetic-reward
 * mapping) — this is the durable earned-achievement record itself.
 */
@OptIn(ExperimentalTime::class)
interface AchievementRepository {

    /**
     * Record an achievement as earned. Idempotent on `(userId, achievementId)`
     * — first write wins on [earnedAt] (a later re-earn keeps the original
     * timestamp). Returns the persisted row.
     */
    suspend fun recordEarned(userId: UserId, achievementId: String, earnedAt: Instant): EarnedAchievement

    /** The user's full authoritative earned set. */
    suspend fun listEarned(userId: UserId): List<EarnedAchievement>

    /**
     * Wipe earned achievements for a user. Called from the `DELETE /v1/me`
     * cascade so account-delete doesn't leave orphan rows.
     */
    suspend fun deleteAllForUser(userId: UserId)
}

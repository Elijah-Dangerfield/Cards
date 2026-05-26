package com.dangerfield.cards.libraries.cards

/**
 * Notify the server about a freshly-earned achievement so it can decide
 * whether to grant a cosmetic into the user's inventory.
 *
 * Achievements live entirely on the client — the server doesn't know
 * which counters / criteria a hand bumped. This API is the seam that
 * lets server-side `AchievementRewards` map the client's "I unlocked X"
 * signal to "grant productId Y." Idempotent server-side via the inventory
 * `(userId, productId)` primary key; safe to re-fire from a retry / replay.
 *
 * Decoupled from [InventoryRepository] so the achievement engine can
 * trigger grants without taking a dependency on the inventory pipeline —
 * the impl handles the inventory-sync trigger internally when a grant
 * lands. Common achievements that carry no cosmetic reward (most of the
 * registry) resolve to a no-op 204 server-side and return false.
 */
interface AchievementGrantApi {

    /**
     * POST the grant. Returns true when the server granted (or had
     * already granted) a cosmetic — caller should re-sync inventory to
     * pick the row up locally. Returns false when there is no reward
     * mapping for this achievement OR when the call failed (network /
     * transient error). Either way the call never throws — the
     * achievement-recording flow must not break because a grant POST
     * failed.
     */
    suspend fun grantAchievement(achievementId: AchievementId): Boolean
}

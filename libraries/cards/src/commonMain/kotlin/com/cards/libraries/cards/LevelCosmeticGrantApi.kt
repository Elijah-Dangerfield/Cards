package com.dangerfield.cards.libraries.cards

/**
 * Notify the server that a level-up earned a cosmetic so it can record the
 * grant into the user's inventory.
 *
 * Mirrors [AchievementGrantApi]: the level-up itself is derived offline from
 * `total_xp` ([com.dangerfield.cards.libraries.cards.levelProgressFor]), but a
 * cosmetic is a server-owned inventory item, so the grant is a best-effort POST.
 * The server's level-grant allowlist decides whether the requested product is
 * grantable — a client can't self-grant an arbitrary catalog cosmetic.
 * Idempotent server-side on `(userId, productId)`; safe to re-fire on retry /
 * replay.
 */
interface LevelCosmeticGrantApi {

    /**
     * POST the grant. Returns true when the server granted (or had already
     * granted) the cosmetic — caller should re-sync inventory to pick the row
     * up locally. Returns false when the product isn't level-grantable, isn't
     * in the catalog, or the call failed (network / transient error). Never
     * throws — the level-up grant flow must not break because a grant POST
     * failed; the next inventory sync catches anything missed.
     */
    suspend fun grantLevelCosmetic(productId: String): Boolean
}

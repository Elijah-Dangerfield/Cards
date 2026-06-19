package com.dangerfield.cards.server.domain

/**
 * Catalog products a client may legitimately self-grant by crossing a level —
 * the level-up counterpart to [ClientGrantableAchievements].
 *
 * **Contract:** the client level-reward table (`progression.levelRewards`
 * app-config) names which cosmetic a level hands out, but the server is the gate
 * on *whether* that product is grantable for free. Without this allowlist a
 * malicious client could POST `/v1/me/grants/level-cosmetic/{anyProductId}` with
 * its own token and mint any cosmetic in the catalog. A product not in
 * [productIds] resolves to a 403 — the same "I know better, refuse" posture as a
 * server-witnessed achievement.
 *
 * Turn a level cosmetic live by adding its product id here *and* to the level
 * table in app-config; both must agree. [Default] is empty — no level grants a
 * cosmetic until the economy is tuned. Idempotency stays with
 * [InventoryRepository.recordEarnedGrant].
 */
class LevelGrantableProducts(
    private val productIds: Set<String>,
) {
    fun isGrantable(productId: String): Boolean = productId in productIds

    companion object {
        val Default: LevelGrantableProducts = LevelGrantableProducts(productIds = emptySet())
    }
}

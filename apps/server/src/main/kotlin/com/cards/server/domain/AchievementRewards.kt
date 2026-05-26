package com.dangerfield.cards.server.domain

/**
 * Server-authoritative mapping from a (client-tracked) achievement id to
 * the catalog product id that should land in inventory when the user
 * earns it.
 *
 * Achievements themselves live entirely on the client today — the server
 * has no idea what counters or rules unlock which achievement. The grant
 * surface here is just "client tells us an achievement unlocked, we
 * decide whether that achievement carries an inventory reward and, if
 * so, which product." First-grant-wins via [InventoryRepository.recordEarnedGrant];
 * a duplicate POST for the same achievement is a no-op.
 *
 * Only legendary / structural achievements get cosmetic rewards. Common
 * milestones (hands played, first win by fold, etc.) carry XP / chips
 * only — that's by design; the unlock-only catalog is supposed to feel
 * rare. Keep this list short and bias it toward achievements seeded with
 * a matching `unlock_only = TRUE` row in the products migrations.
 *
 * The mapping is server-side rather than client-side so the client never
 * needs to know which products exist — adding a new reward only requires
 * a server change (plus the migration for the cosmetic itself).
 */
object AchievementRewards {

    private val mapping: Map<String, String> = mapOf(
        "POT_5000" to "title_pot_magnet",
        "COMEBACK_FROM_5BB" to "title_short_stack_hero",
    )

    /**
     * Returns the productId to grant when [achievementId] unlocks, or
     * null when the achievement has no inventory reward (the common
     * case). Unknown achievement ids also fall through to null —
     * clients on a newer build than the server simply receive a 204.
     */
    fun productIdFor(achievementId: String): String? = mapping[achievementId]
}

package com.dangerfield.cards.libraries.cards

/**
 * Client-side hint for the achievement → inventory grant mapping the
 * **server** owns (see `apps/server/.../ClientGrantableAchievements.kt`).
 *
 * The server is authoritative — it validates the achievement id and
 * actually writes the inventory row when the client POSTs
 * `grants/achievement/{id}`. This file is a UI-only mirror so the play
 * screen can render "Also unlocked: {label}" alongside the achievement
 * callout at earn-time without waiting on a server round-trip or the
 * subsequent inventory sync.
 *
 * **Keep in sync with the server.** Drift is a UX bug, not a security
 * one — the server still rejects unknown ids — but a missing entry here
 * means the user earns a cosmetic and gets no acknowledgement of it.
 */

/**
 * Cosmetic that gets granted into the user's inventory when an achievement
 * unlocks. [label] is the human-readable name shown in unlock celebrations
 * (e.g. "Pot Magnet title", "Comeback Kid card back"); it carries the
 * cosmetic family so the user knows *what kind of thing* they just unlocked
 * without an extra tap into My Items.
 */
data class CosmeticReward(
    val productId: String,
    val label: String,
)

/**
 * Returns the cosmetic an unlocked [id] grants into inventory, or `null`
 * when the achievement carries XP/chip rewards only. Mirrors the server's
 * `ClientGrantableAchievements.Default` allowlist.
 */
fun cosmeticRewardFor(id: AchievementId): CosmeticReward? = when (id) {
    AchievementId.POT_5000 -> CosmeticReward(
        productId = "title_pot_magnet",
        label = "Pot Magnet title",
    )
    AchievementId.COMEBACK_FROM_5BB -> CosmeticReward(
        productId = "title_short_stack_hero",
        label = "Short Stack Hero title",
    )
    AchievementId.DONT_CALL_IT_COMEBACK -> CosmeticReward(
        productId = "cardback_comeback_kid",
        label = "Comeback Kid card back",
    )
    AchievementId.BOT_WHISPERER -> CosmeticReward(
        productId = "title_bot_whisperer",
        label = "Bot Whisperer title",
    )
    AchievementId.BUST_DEALT_5 -> CosmeticReward(
        productId = "emotes_eliminator",
        label = "Eliminator emote pack",
    )
    else -> null
}

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
 * Catalog-axis tag attached to every [CosmeticReward]. Describes whether
 * the cosmetic is reachable through one path (achievement-only or
 * shop-only) or both (`EARN_OR_BUY` — the same productId is granted on
 * achievement unlock *and* listed in the shop catalog).
 *
 * The shop / inventory surfaces use this to render the right badge:
 *  - `EARN_ONLY` → "Earned" badge always; never appears in the shop list.
 *  - `EARN_OR_BUY` → "Earned" badge on the achievement-granted instance;
 *    same item without the badge when bought.
 *  - `BUY_ONLY` → no badge; only path is the shop. Doesn't appear in this
 *    file because there's no achievement → grant mapping by definition.
 *
 * V1 state (2026-05-29): the V1 catalog ships every earnable cosmetic
 * as `unlock_only = TRUE` server-side ([V17, V20, V27-V34 migrations]),
 * so every entry below is [EARN_ONLY]. The [EARN_OR_BUY] tier is reserved
 * for the future seed entries called out in [docs/todo.md §A "Catalog
 * gating"] — the heat-map widget and W/L-odds display — where the same
 * productId will land in both the earnable map *and* the shop catalog
 * (without `unlock_only`).
 */
enum class CosmeticTier {
    /** Achievement-only. The productId is `unlock_only = TRUE` server-side. */
    EARN_ONLY,

    /**
     * Lives in both catalogs. Achievement-granted instances carry an
     * "Earned" badge; bought instances don't. No entries yet in V1 — see
     * the docs/todo.md "Catalog gating" item for the planned seeds.
     */
    EARN_OR_BUY,
}

/**
 * Cosmetic that gets granted into the user's inventory when an achievement
 * unlocks. [label] is the human-readable name shown in unlock celebrations
 * (e.g. "Pot Magnet title", "Comeback Kid card back"); it carries the
 * cosmetic family so the user knows *what kind of thing* they just unlocked
 * without an extra tap into My Items. [tier] is the catalog-axis tag — see
 * [CosmeticTier].
 */
data class CosmeticReward(
    val productId: String,
    val label: String,
    val tier: CosmeticTier = CosmeticTier.EARN_ONLY,
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
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.COMEBACK_FROM_5BB -> CosmeticReward(
        productId = "title_short_stack_hero",
        label = "Short Stack Hero title",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.DONT_CALL_IT_COMEBACK -> CosmeticReward(
        productId = "cardback_comeback_kid",
        label = "Comeback Kid card back",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.BOT_WHISPERER -> CosmeticReward(
        productId = "title_bot_whisperer",
        label = "Bot Whisperer title",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.BUST_DEALT_5 -> CosmeticReward(
        productId = "emotes_eliminator",
        label = "Eliminator emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.TRIPLE_UP -> CosmeticReward(
        productId = "emotes_baller",
        label = "Baller emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.NO_BUST_100 -> CosmeticReward(
        productId = "emotes_iron_stack",
        label = "Iron Stack emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.WIN_BY_FOLD_10 -> CosmeticReward(
        productId = "emotes_convincer",
        label = "Convincer emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.GOOD_FOLD_25 -> CosmeticReward(
        productId = "emotes_disciplined",
        label = "Disciplined emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.HANDS_1000 -> CosmeticReward(
        productId = "emotes_grinder",
        label = "Grinder emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.DOUBLE_UP -> CosmeticReward(
        productId = "emotes_doubler",
        label = "Doubler emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.CHALLENGING_10_WINS -> CosmeticReward(
        productId = "emotes_tactician",
        label = "Tactician emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.REACH_LEVEL_25 -> CosmeticReward(
        productId = "title_felt_veteran",
        label = "Felt Veteran title",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.BEAT_JANE_10 -> CosmeticReward(
        productId = "emotes_inspector",
        label = "Inspector emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.BEAT_DAVID_10 -> CosmeticReward(
        productId = "emotes_showstopper",
        label = "Showstopper emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.BEAT_GINA_10 -> CosmeticReward(
        productId = "emotes_outsmarter",
        label = "Outsmarter emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.BEAT_STEVE_10 -> CosmeticReward(
        productId = "emotes_marathoner",
        label = "Marathoner emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    AchievementId.BEAT_MIKE_10 -> CosmeticReward(
        productId = "emotes_tamer",
        label = "Tamer emote pack",
        tier = CosmeticTier.EARN_ONLY,
    )
    else -> null
}

/**
 * Inverse of [cosmeticRewardFor]: returns the [CosmeticTier] for an
 * arbitrary [productId], or `null` when the product doesn't appear in
 * the achievement-grant map at all (i.e. it's a `BUY_ONLY` shop product).
 *
 * Cached on first call so the inventory + shop render paths don't pay
 * a per-row enum-scan; the underlying mapping is small and immutable.
 *
 * Render usage:
 *  - `EARN_ONLY` → never visible in the shop catalog server-side
 *    (`unlock_only = TRUE`); in inventory, the "Earned" pin always
 *    applies because there's no buy path.
 *  - `EARN_OR_BUY` → visible in both. Inventory rows distinguish the
 *    earn-grant variant from a chip-bought duplicate with a richer
 *    achievement badge.
 *  - `null` → BUY_ONLY product; no provenance badge.
 */
fun tierForProductId(productId: String): CosmeticTier? =
    productIdToTier[productId]

private val productIdToTier: Map<String, CosmeticTier> by lazy {
    AchievementId.entries
        .mapNotNull { id -> cosmeticRewardFor(id)?.let { it.productId to it.tier } }
        .toMap()
}

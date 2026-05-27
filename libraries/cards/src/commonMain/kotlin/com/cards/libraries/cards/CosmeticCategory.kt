package com.dangerfield.cards.libraries.cards

/**
 * Logical slot a cosmetic occupies. The rendering layer treats each slot
 * as single-equip (only one felt visible at a time; only one card back
 * shows on the table; only one title appears under the avatar). Packs
 * that grant a bag of things (avatar packs, emote packs) don't claim a
 * slot — the user can own and use multiple at once.
 *
 * Currently derived from the server-side product-id prefix conventions
 * baked into `apps/server/src/main/resources/db/migration/V5__products.sql`.
 * Centralised here so the shop's auto-equip-on-purchase rule and any
 * future "is this conflicting?" check stays consistent across features.
 */
enum class CosmeticSlot {
    Felt,
    CardBack,
    Title,
    Tool,
    Badge,
}

/**
 * Returns the slot a product occupies, or null if it isn't slot-based
 * (chip packs, avatar packs, emote packs, anything the helper doesn't
 * recognize). Unknown ids fall through to null on purpose — we'd rather
 * skip auto-equip than guess wrong.
 */
fun cosmeticSlotFor(productId: String): CosmeticSlot? = when {
    productId.startsWith("felt_") || productId.startsWith("table_") -> CosmeticSlot.Felt
    productId.startsWith("cardback_") -> CosmeticSlot.CardBack
    productId.startsWith("title_") -> CosmeticSlot.Title
    productId.startsWith("tool_") -> CosmeticSlot.Tool
    productId.startsWith("badge_") -> CosmeticSlot.Badge
    else -> null
}

/**
 * Whether a cosmetic is *only* rendered for the wearer (felt under the
 * cards, card back of your own hand, utility overlays like Win Odds) vs.
 * visible to the rest of the table (player titles, emote blasts).
 *
 * Buying a personal cosmetic expecting other seats to see it is the
 * sharpest paper cut the shop produces today — so we surface it as a
 * small "Only visible to you" badge on both the shop product card and
 * the equipped-item tile in My Items.
 *
 * Single source of truth so the shop and My Items can't drift on the
 * same idea. Defaults to false (public) for unrecognised ids — better
 * to under-label than to mis-label a future emote pack as personal.
 */
fun isPersonalCosmetic(productId: String): Boolean = when (cosmeticSlotFor(productId)) {
    CosmeticSlot.Felt -> true
    CosmeticSlot.CardBack -> true
    CosmeticSlot.Tool -> true
    CosmeticSlot.Title -> false
    CosmeticSlot.Badge -> false
    null -> false
}

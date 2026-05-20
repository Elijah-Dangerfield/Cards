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
    else -> null
}

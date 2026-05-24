package com.dangerfield.cards.features.room.impl

/**
 * Client-side mapping from owned emote-pack product IDs to the emojis those
 * packs unlock for in-game blasts. Mirrors the seed data in
 * `apps/server/.../V5__products.sql` + the localized copy in
 * `V14__emote_pack_copy.sql` — both files name the same emoji set per pack.
 *
 * The server is authoritative for *what's for sale* and *what the user owns*
 * (catalog + inventory). It does not ship the per-pack emoji list to the
 * client because today there's no other surface that needs it: the blast
 * tray reads from this constant table. If we ever want to drop a pack
 * without shipping an app update, this moves to a server-driven payload —
 * for now the constant table keeps the wire format flat.
 *
 * Base pool ([BaseEmojiPool]) matches product-spec.md §5.5 and is always
 * available regardless of ownership. Owned packs concatenate onto it; the
 * tray dedupes so the user never sees the same emoji twice (e.g. 🔥 lives
 * in both the base pool and the Fierce pack).
 */
internal object EmojiPackCatalog {

    /**
     * Always-available pool. 12 emojis from product-spec.md §5.5. Order
     * matters — it's the rendering order in the tray.
     */
    val BaseEmojiPool: List<String> = listOf(
        "🔥", "🎉", "😱", "🤡", "💀", "👀", "🥶", "🤯", "💸", "🙏", "😎", "🥲",
    )

    /**
     * `productId` → emoji list. Keys match `apps/server/.../V5__products.sql`;
     * values match the localized "Unlocks ..." copy in `V14__emote_pack_copy.sql`.
     */
    private val PackEmojis: Map<String, List<String>> = mapOf(
        "emotes_drama" to listOf("💃", "🧂", "🎭", "🤦"),
        "emotes_cute" to listOf("🥺", "🥰", "😇", "🤗"),
        "emotes_fierce" to listOf("😤", "🔥", "💀", "😎"),
        "emotes_royal" to listOf("👑", "🃏", "♠️", "♥️"),
    )

    /**
     * Available blast pool given the user's owned inventory IDs. Base pool
     * first (always present), then pack-unlocked emojis in pack-key order,
     * deduped. Order is stable so the tray doesn't shuffle when a new pack
     * is acquired mid-session.
     */
    fun availableEmojisFor(ownedProductIds: Set<String>): List<String> {
        val result = LinkedHashSet<String>(BaseEmojiPool.size + 16)
        result.addAll(BaseEmojiPool)
        PackEmojis.forEach { (productId, emojis) ->
            if (productId in ownedProductIds) result.addAll(emojis)
        }
        return result.toList()
    }
}

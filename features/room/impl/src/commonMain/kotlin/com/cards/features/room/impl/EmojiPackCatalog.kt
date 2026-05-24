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
 * Emoji blasts are a paid surface — a user with no `emotes_*` pack gets
 * nothing back from [availableEmojisFor], which is what the screen uses
 * to decide whether to render the tray at all.
 */
internal object EmojiPackCatalog {

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
     * Available blast pool given the user's owned inventory IDs. Pack-
     * unlocked emojis in pack-key order, deduped. Order is stable so the
     * tray doesn't shuffle when a new pack is acquired mid-session. Empty
     * when the user owns no emote packs — callers should hide the tray
     * UI entirely in that case rather than rendering an empty picker.
     */
    fun availableEmojisFor(ownedProductIds: Set<String>): List<String> {
        val result = LinkedHashSet<String>(16)
        PackEmojis.forEach { (productId, emojis) ->
            if (productId in ownedProductIds) result.addAll(emojis)
        }
        return result.toList()
    }
}

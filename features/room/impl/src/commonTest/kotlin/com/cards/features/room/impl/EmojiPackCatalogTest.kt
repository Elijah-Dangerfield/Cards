package com.dangerfield.cards.features.room.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiPackCatalogTest {

    @Test
    fun noOwnedPacks_returnsEmpty() {
        // Emoji blasts are a paid surface — no pack, no emojis. Caller
        // is expected to hide the tray entirely when this list is empty.
        val available = EmojiPackCatalog.availableEmojisFor(ownedProductIds = emptySet())
        assertTrue(available.isEmpty())
    }

    @Test
    fun unknownProductIds_areIgnored() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("not_a_pack", "tool_win_odds"),
        )
        assertTrue(available.isEmpty())
    }

    @Test
    fun ownedPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_cute"),
        )
        assertEquals(listOf("🥺", "🥰", "😇", "🤗"), available)
    }

    @Test
    fun ownedMultiplePacks_appendInPackOrder_deduped() {
        val available = EmojiPackCatalog.availableEmojisFor(
            setOf("emotes_cute", "emotes_drama"),
        )
        // Drama comes first in PackEmojis insertion order regardless of
        // the input set's iteration order.
        assertEquals(
            listOf("💃", "🧂", "🎭", "🤦", "🥺", "🥰", "😇", "🤗"),
            available,
        )
        assertEquals(available.size, available.distinct().size)
        // Set ordering of input doesn't matter — the same packs always
        // produce the same available list regardless of which order the
        // caller iterates `ownedProductIds`.
        val reordered = EmojiPackCatalog.availableEmojisFor(
            setOf("emotes_drama", "emotes_cute"),
        )
        assertEquals(available, reordered)
    }
}

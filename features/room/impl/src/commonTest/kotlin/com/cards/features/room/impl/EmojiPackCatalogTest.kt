package com.dangerfield.cards.features.room.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiPackCatalogTest {

    @Test
    fun basePool_isAlwaysAvailable_withNoOwnedPacks() {
        val available = EmojiPackCatalog.availableEmojisFor(ownedProductIds = emptySet())
        assertEquals(EmojiPackCatalog.BaseEmojiPool, available)
    }

    @Test
    fun unknownProductIds_areIgnored() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("not_a_pack", "tool_win_odds"),
        )
        assertEquals(EmojiPackCatalog.BaseEmojiPool, available)
    }

    @Test
    fun ownedPack_appendsItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_cute"),
        )
        assertTrue(available.containsAll(EmojiPackCatalog.BaseEmojiPool))
        assertTrue("🥺" in available)
        assertTrue("🥰" in available)
        assertTrue("😇" in available)
        assertTrue("🤗" in available)
    }

    @Test
    fun overlappingEmojis_areDedupedToFirstOccurrence() {
        // Fierce pack shares 🔥, 💀, 😎 with the base pool. Those should
        // not appear twice — the tray would render the same emoji at two
        // positions otherwise.
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_fierce"),
        )
        assertEquals(available.size, available.distinct().size)
        // Base-pool 🔥 stays at its original index; the pack-side dup is dropped.
        assertEquals(EmojiPackCatalog.BaseEmojiPool.indexOf("🔥"), available.indexOf("🔥"))
    }

    @Test
    fun ownedMultiplePacks_appendAllUniqueEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            setOf("emotes_cute", "emotes_drama"),
        )
        val expectedAdditions = listOf("🥺", "🥰", "😇", "🤗", "💃", "🧂", "🎭", "🤦")
        expectedAdditions.forEach { emoji ->
            assertTrue(emoji in available, "expected $emoji in available list")
        }
        // Base pool stays at the head, in original order.
        assertEquals(
            EmojiPackCatalog.BaseEmojiPool,
            available.take(EmojiPackCatalog.BaseEmojiPool.size),
        )
        // Set ordering of input doesn't matter — the same packs always
        // produce the same available list regardless of which order the
        // caller iterates `ownedProductIds`.
        val reordered = EmojiPackCatalog.availableEmojisFor(
            setOf("emotes_drama", "emotes_cute"),
        )
        assertEquals(available, reordered)
    }
}

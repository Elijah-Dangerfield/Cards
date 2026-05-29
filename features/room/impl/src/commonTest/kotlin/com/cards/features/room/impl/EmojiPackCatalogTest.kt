package com.dangerfield.cards.features.room.impl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmojiPackCatalogTest {

    @Test
    fun noOwnedPacks_returnsEmpty() {
        // Emoji blasts are a paid surface — no pack, no emojis. The tray's
        // empty-state popup (greyed-out preview + shop CTA) renders
        // [EmojiPackCatalog.SamplePreview] when this list is empty.
        val available = EmojiPackCatalog.availableEmojisFor(ownedProductIds = emptySet())
        assertTrue(available.isEmpty())
    }

    @Test
    fun samplePreview_isFourGlyphsFromShippingPacks() {
        // The empty-state popup needs a non-empty, fixed-length preview
        // so its layout is stable. Each glyph must be one we actually
        // ship (otherwise the user buys a pack and discovers a different
        // emoji set than the teaser implied).
        val preview = EmojiPackCatalog.SamplePreview
        assertEquals(4, preview.size)
        val allShippingEmojis = EmojiPackCatalog
            .availableEmojisFor(setOf(
                "emotes_drama", "emotes_cute", "emotes_fierce", "emotes_royal",
                "emotes_eliminator", "emotes_baller", "emotes_iron_stack",
                "emotes_convincer", "emotes_disciplined", "emotes_grinder",
                "emotes_doubler", "emotes_tactician",
                "emotes_inspector", "emotes_showstopper", "emotes_outsmarter",
                "emotes_marathoner", "emotes_tamer",
            ))
            .toSet()
        preview.forEach { glyph ->
            assertTrue(
                glyph in allShippingEmojis,
                "Preview glyph $glyph is not shipped by any pack",
            )
        }
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
    fun eliminatorPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_eliminator"),
        )
        assertEquals(listOf("🪦", "⚰️", "👻", "🥀"), available)
    }

    @Test
    fun ballerPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_baller"),
        )
        assertEquals(listOf("💸", "💎", "🤑", "📈"), available)
    }

    @Test
    fun ironStackPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_iron_stack"),
        )
        assertEquals(listOf("🛡️", "🧱", "🗿", "🦾"), available)
    }

    @Test
    fun convincerPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_convincer"),
        )
        assertEquals(listOf("🪄", "🎩", "😏", "🤫"), available)
    }

    @Test
    fun disciplinedPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_disciplined"),
        )
        assertEquals(listOf("🧘", "🦉", "👁️", "🪞"), available)
    }

    @Test
    fun grinderPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_grinder"),
        )
        assertEquals(listOf("☕", "⛏️", "🛠️", "⌛"), available)
    }

    @Test
    fun doublerPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_doubler"),
        )
        assertEquals(listOf("🚀", "⏫", "🎯", "💰"), available)
    }

    @Test
    fun tacticianPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_tactician"),
        )
        assertEquals(listOf("♟️", "🦅", "🥷", "🏹"), available)
    }

    @Test
    fun inspectorPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_inspector"),
        )
        assertEquals(listOf("🔍", "📋", "🤓", "☝️"), available)
    }

    @Test
    fun showstopperPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_showstopper"),
        )
        assertEquals(listOf("🎤", "✨", "👏", "🎬"), available)
    }

    @Test
    fun outsmarterPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_outsmarter"),
        )
        assertEquals(listOf("💡", "🪤", "🕸️", "🔮"), available)
    }

    @Test
    fun marathonerPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_marathoner"),
        )
        assertEquals(listOf("🦥", "🐌", "🪨", "🌅"), available)
    }

    @Test
    fun tamerPack_unlocksItsEmojis() {
        val available = EmojiPackCatalog.availableEmojisFor(
            ownedProductIds = setOf("emotes_tamer"),
        )
        assertEquals(listOf("🦁", "🎪", "🤹", "🪅"), available)
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

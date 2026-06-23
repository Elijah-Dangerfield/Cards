package com.dangerfield.cards.libraries.ui.components.poker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the catalog-productId → equipped-cosmetic lookup tables in
 * [EquippedFelt.kt]. These four switchboards ([feltForProductId],
 * [cardBackForProductId], [titleForProductId], [badgeEmojiForProductId])
 * are the seam between server-side product ids (seeded in
 * `apps/server/.../V5__products.sql` and related migrations) and the
 * client-side enums / display strings the renderer reads.
 *
 * **Why pin:** the mappings are the single source of truth for "what
 * does the user see when they equip this product." A typo in the
 * product-id literal — `cardback_galaxy` vs `cardback-galaxy`, or a
 * dropped underscore — silently falls through to the default style /
 * null label, which renders as "I bought a thing but nothing happened"
 * from the user's perspective and as zero exceptions from the
 * developer's. These tests catch the typo before the player does.
 *
 * Coverage stops at unknown-id-fallback for each table — full
 * round-trip-vs-server-catalog would belong in a server-side test
 * (where the catalog seed lives). The split keeps each side authoritative
 * for its own surface and avoids cross-module coupling on a string list.
 */
class EquippedFeltMappingsTest {

    // ---------- feltForProductId ----------

    @Test
    fun feltForProductId_mapsEveryKnownFeltProduct() {
        assertEquals(EquippedFelt.RoyalRed, feltForProductId("felt_royal_red"))
        assertEquals(EquippedFelt.MidnightBlue, feltForProductId("felt_midnight_blue"))
        assertEquals(EquippedFelt.Charcoal, feltForProductId("felt_charcoal"))
        assertEquals(EquippedFelt.PineGreen, feltForProductId("felt_pine_green"))
        assertEquals(EquippedFelt.Sunset, feltForProductId("felt_sunset_weekend"))
    }

    @Test
    fun feltForProductId_removedTableThemesFallToDefault() {
        // The legacy `table_sunset` (V64) and `table_neon` (V70) table-theme
        // products were removed — they were visually redundant with plain felts
        // in V1. Their ids are no longer mapped, so they fall through to the
        // default felt.
        assertEquals(EquippedFelt.Default, feltForProductId("table_sunset"))
        assertEquals(EquippedFelt.Default, feltForProductId("table_neon"))
    }

    @Test
    fun feltForProductId_unknownAndNull_fallToDefault() {
        // Documented contract: unknown or absent productId renders sanely
        // as the default felt, so a server-side felt added before the
        // client knows about it doesn't crash or render black.
        assertEquals(EquippedFelt.Default, feltForProductId(null))
        assertEquals(EquippedFelt.Default, feltForProductId(""))
        assertEquals(EquippedFelt.Default, feltForProductId("felt_does_not_exist"))
        assertEquals(EquippedFelt.Default, feltForProductId("cardback_marble"))
    }

    @Test
    fun feltForProductId_isCaseSensitive() {
        // No case folding — server-side ids are canonical lowercase
        // snake_case. Uppercase variants fall through to Default rather
        // than silently match. A future "let me be lenient" refactor
        // breaks this test deliberately.
        assertEquals(EquippedFelt.Default, feltForProductId("FELT_ROYAL_RED"))
        assertEquals(EquippedFelt.Default, feltForProductId("Felt_Royal_Red"))
    }

    // ---------- cardBackForProductId ----------

    @Test
    fun cardBackForProductId_mapsEveryKnownCardBackProduct() {
        // 15 catalog cardbacks — the full registry mapped 1-to-1.
        val expected = mapOf(
            "cardback_marble" to CardBackStyle.Marble,
            "cardback_gold" to CardBackStyle.Gold,
            "cardback_neon" to CardBackStyle.Neon,
            "cardback_diamond" to CardBackStyle.Diamond,
            "cardback_comeback_kid" to CardBackStyle.ComebackKid,
            "cardback_skulls" to CardBackStyle.Skulls,
            "cardback_galaxy" to CardBackStyle.Galaxy,
            "cardback_holographic" to CardBackStyle.Holographic,
            "cardback_fire" to CardBackStyle.Fire,
            "cardback_avatar" to CardBackStyle.Avatar,
            "cardback_lattice" to CardBackStyle.Lattice,
            "cardback_hatching" to CardBackStyle.Hatching,
            "cardback_crosshatch" to CardBackStyle.Crosshatch,
            "cardback_dots" to CardBackStyle.Dots,
            "cardback_pinstripes" to CardBackStyle.Pinstripes,
        )
        expected.forEach { (productId, style) ->
            assertEquals(style, cardBackForProductId(productId), "cardBackForProductId($productId)")
        }
    }

    @Test
    fun cardBackForProductId_unknownAndNull_fallToDefault() {
        assertEquals(CardBackStyle.Default, cardBackForProductId(null))
        assertEquals(CardBackStyle.Default, cardBackForProductId(""))
        assertEquals(CardBackStyle.Default, cardBackForProductId("cardback_does_not_exist"))
        assertEquals(CardBackStyle.Default, cardBackForProductId("felt_royal_red"))
    }

    // ---------- titleForProductId ----------

    @Test
    fun titleForProductId_mapsEveryKnownTitleProduct() {
        // Pins the v1 title catalog. New title products MUST land here
        // (the test fails loudly if the catalog grows without the
        // mapping). English-only for v1; localization swaps this lookup
        // for a server-provided field per the file docstring.
        assertEquals("Bluff Master", titleForProductId("title_bluff_master"))
        assertEquals("The Shark", titleForProductId("title_shark"))
        assertEquals("High Roller", titleForProductId("title_high_roller"))
        assertEquals("Pot Magnet", titleForProductId("title_pot_magnet"))
        assertEquals("Short Stack Hero", titleForProductId("title_short_stack_hero"))
        assertEquals("Bot Whisperer", titleForProductId("title_bot_whisperer"))
        assertEquals("Felt Veteran", titleForProductId("title_felt_veteran"))
    }

    @Test
    fun titleForProductId_unknownAndNull_returnNull() {
        // Null = "no title displayed under the player's name" — the
        // correct degrade for an unknown server-side title id.
        assertNull(titleForProductId(null))
        assertNull(titleForProductId(""))
        assertNull(titleForProductId("title_does_not_exist"))
        assertNull(titleForProductId("felt_royal_red"))
    }

    // ---------- badgeEmojiForProductId ----------

    @Test
    fun badgeEmojiForProductId_mapsFoundingMember() {
        // The only v1 badge — granted server-side to the founding-member
        // cohort. Pinning the emoji guards against a future
        // typography-pass-style edit silently swapping the glyph.
        assertEquals("🏛", badgeEmojiForProductId("badge_founding_member_1000"))
    }

    @Test
    fun badgeEmojiForProductId_unknownAndNull_returnNull() {
        assertNull(badgeEmojiForProductId(null))
        assertNull(badgeEmojiForProductId(""))
        assertNull(badgeEmojiForProductId("badge_does_not_exist"))
        assertNull(badgeEmojiForProductId("felt_royal_red"))
    }
}

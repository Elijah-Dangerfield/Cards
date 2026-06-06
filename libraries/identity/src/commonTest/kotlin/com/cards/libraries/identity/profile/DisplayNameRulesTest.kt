package com.dangerfield.cards.libraries.identity.profile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayNameRulesTest {

    @Test
    fun names_shorter_than_three_chars_are_invalid() {
        assertFalse(DisplayNameRules.isValid(""))
        assertFalse(DisplayNameRules.isValid("a"))
        assertFalse(DisplayNameRules.isValid("ab"))
    }

    @Test
    fun three_chars_is_the_minimum_accepted() {
        assertTrue(DisplayNameRules.isValid("abc"))
    }

    @Test
    fun length_is_measured_after_trimming() {
        // Surrounding whitespace trims away to two real chars → too short.
        assertFalse(DisplayNameRules.isValid("  ab  "))
        assertTrue(DisplayNameRules.isValid("  abc  "))
    }

    @Test
    fun names_at_and_over_the_max_length() {
        assertTrue(DisplayNameRules.isValid("a".repeat(DisplayNameRules.MAX_LENGTH)))
        assertFalse(DisplayNameRules.isValid("a".repeat(DisplayNameRules.MAX_LENGTH + 1)))
    }

    @Test
    fun ordinary_alphanumeric_and_accented_names_are_valid() {
        assertTrue(DisplayNameRules.isValid("QuietAce72"))
        assertTrue(DisplayNameRules.isValid("José"))
        assertTrue(DisplayNameRules.isValid("a_b-c.d"))
    }

    @Test
    fun emoji_names_are_rejected_even_when_length_is_fine() {
        // Astral-plane emoji (surrogate pair).
        assertFalse(DisplayNameRules.isValid("Ace😀X"))
        // BMP pictograph.
        assertFalse(DisplayNameRules.isValid("luck★star"))
        // Regional-indicator flag.
        assertFalse(DisplayNameRules.isValid("USA🇺🇸fan"))
        // ZWJ-joined family sequence.
        assertFalse(DisplayNameRules.isValid("fam👨‍👩‍👧"))
        // Keycap sequence.
        assertFalse(DisplayNameRules.isValid("num1️⃣x"))
    }

    @Test
    fun containsEmoji_distinguishes_plain_text_from_emoji() {
        assertFalse(DisplayNameRules.containsEmoji("PlainName123"))
        assertTrue(DisplayNameRules.containsEmoji("😀"))
        assertTrue(DisplayNameRules.containsEmoji("❤"))
    }
}

package com.dangerfield.cards.server.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AvatarPaletteTest {

    @Test
    fun values_areAllLowercaseSixDigitHex() {
        val hexPattern = Regex("^#[0-9a-f]{6}$")
        AvatarPalette.values.forEach { hex ->
            assertTrue(
                hexPattern.matches(hex),
                "Palette entry '$hex' must be #rrggbb lowercase (6 hex digits, leading '#').",
            )
        }
    }

    @Test
    fun values_haveNoDuplicates() {
        val distinct = AvatarPalette.values.distinct()
        assertEquals(
            AvatarPalette.values.size,
            distinct.size,
            "Palette has duplicate entries: ${AvatarPalette.values - distinct.toSet()}",
        )
    }

    @Test
    fun isValid_acceptsKnownPaletteEntry() {
        AvatarPalette.values.forEach { hex ->
            assertTrue(
                AvatarPalette.isValid(hex),
                "Palette member '$hex' should validate as in-set.",
            )
        }
    }

    @Test
    fun isValid_acceptsUppercaseInputAgainstLowercasePalette() {
        val mixedCase = AvatarPalette.values.first().uppercase()
        assertTrue(
            AvatarPalette.isValid(mixedCase),
            "Uppercase input should normalize to the lowercase palette via .lowercase() — " +
                "the patchMe path relies on this so clients sending '#5BC79B' don't 400.",
        )
    }

    @Test
    fun isValid_rejectsHexNotInPalette() {
        assertFalse(AvatarPalette.isValid("#000000"))
        assertFalse(AvatarPalette.isValid("#ffffff"))
        assertFalse(AvatarPalette.isValid("#123456"))
    }

    @Test
    fun isValid_rejectsMalformedInputs() {
        assertFalse(AvatarPalette.isValid(""))
        assertFalse(AvatarPalette.isValid("5bc79b"))
        assertFalse(AvatarPalette.isValid("#5bc79"))
        assertFalse(AvatarPalette.isValid("#5bc79bb"))
        assertFalse(AvatarPalette.isValid("not-a-color"))
    }

    @Test
    fun aurora_matchesAccentPrimaryDocumentedValue() {
        assertTrue(
            "#5bc79b" in AvatarPalette.values,
            "Aurora swatch is the documented accentPrimary match — removing it drifts " +
                "the avatar disc background away from the brand accent. If you really " +
                "want it gone, retire it intentionally and update the docstring.",
        )
    }
}

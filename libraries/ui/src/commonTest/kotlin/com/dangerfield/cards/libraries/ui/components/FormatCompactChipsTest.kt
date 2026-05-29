package com.dangerfield.cards.libraries.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `formatCompactChips` shorthand mapping used by the tight chip /
 * count surfaces — `ChipBadge`, `ChipPill`, `ChipCoin`, the table HUD's
 * fixed-width pills. Drift between the truncate-toward-zero rounding and
 * the `k` / `M` boundary checks would silently overstate or understate
 * displayed chip totals, which the comma-grouped `formatThousands` callers
 * would catch but the compact callers wouldn't.
 */
class FormatCompactChipsTest {

    @Test
    fun belowThousand_rendersRawValue() {
        assertEquals("0", formatCompactChips(0L))
        assertEquals("1", formatCompactChips(1L))
        assertEquals("999", formatCompactChips(999L))
    }

    @Test
    fun belowThousand_negative_keepsLeadingMinus() {
        assertEquals("-1", formatCompactChips(-1L))
        assertEquals("-999", formatCompactChips(-999L))
    }

    @Test
    fun thousandToTenThousand_rendersOneFractionalDigit() {
        assertEquals("1k", formatCompactChips(1_000L))
        assertEquals("1.2k", formatCompactChips(1_234L))
        assertEquals("5k", formatCompactChips(5_000L))
        assertEquals("9.9k", formatCompactChips(9_999L))
    }

    @Test
    fun thousandToTenThousand_truncatesTowardZero_doesNotRound() {
        assertEquals("1.9k", formatCompactChips(1_999L))
        assertEquals("1.9k", formatCompactChips(1_950L))
    }

    @Test
    fun thousandToTenThousand_negative_keepsLeadingMinusAndFractional() {
        assertEquals("-1k", formatCompactChips(-1_000L))
        assertEquals("-1.2k", formatCompactChips(-1_234L))
        assertEquals("-9.9k", formatCompactChips(-9_999L))
    }

    @Test
    fun tenThousandToMillion_dropsFractionalDigit() {
        assertEquals("10k", formatCompactChips(10_000L))
        assertEquals("12k", formatCompactChips(12_345L))
        assertEquals("100k", formatCompactChips(100_000L))
        assertEquals("999k", formatCompactChips(999_999L))
    }

    @Test
    fun tenThousandToMillion_negative_keepsLeadingMinus() {
        assertEquals("-10k", formatCompactChips(-10_000L))
        assertEquals("-12k", formatCompactChips(-12_345L))
        assertEquals("-999k", formatCompactChips(-999_999L))
    }

    @Test
    fun millionToTenMillion_rendersOneFractionalDigit() {
        assertEquals("1M", formatCompactChips(1_000_000L))
        assertEquals("1.2M", formatCompactChips(1_234_567L))
        assertEquals("9.9M", formatCompactChips(9_999_999L))
    }

    @Test
    fun millionToTenMillion_negative_keepsLeadingMinusAndFractional() {
        assertEquals("-1M", formatCompactChips(-1_000_000L))
        assertEquals("-1.2M", formatCompactChips(-1_234_567L))
    }

    @Test
    fun aboveTenMillion_dropsFractionalDigit() {
        assertEquals("10M", formatCompactChips(10_000_000L))
        assertEquals("12M", formatCompactChips(12_345_678L))
        assertEquals("1234M", formatCompactChips(1_234_567_890L))
    }

    @Test
    fun aboveTenMillion_negative_keepsLeadingMinus() {
        assertEquals("-10M", formatCompactChips(-10_000_000L))
        assertEquals("-12M", formatCompactChips(-12_345_678L))
    }

    @Test
    fun boundary_ninehundredNinetyNine_isRaw_oneThousand_isCompact() {
        assertEquals("999", formatCompactChips(999L))
        assertEquals("1k", formatCompactChips(1_000L))
    }

    @Test
    fun boundary_nineThousandNineHundredNinetyNine_keepsFraction_tenThousand_dropsFraction() {
        assertEquals("9.9k", formatCompactChips(9_999L))
        assertEquals("10k", formatCompactChips(10_000L))
    }

    @Test
    fun boundary_nineHundredNinetyNineThousand_isK_oneMillion_isM() {
        assertEquals("999k", formatCompactChips(999_999L))
        assertEquals("1M", formatCompactChips(1_000_000L))
    }

    @Test
    fun boundary_nineMillionNineHundredNinetyNine_keepsFraction_tenMillion_dropsFraction() {
        assertEquals("9.9M", formatCompactChips(9_999_999L))
        assertEquals("10M", formatCompactChips(10_000_000L))
    }
}

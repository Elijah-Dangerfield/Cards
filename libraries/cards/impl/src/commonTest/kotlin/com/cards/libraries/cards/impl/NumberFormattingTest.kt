package com.dangerfield.cards.libraries.cards.impl

import com.dangerfield.cards.libraries.cards.formatThousands
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberFormattingTest {

    @Test
    fun belowThousand_rendersWithoutSeparator() {
        assertEquals("0", formatThousands(0L))
        assertEquals("1", formatThousands(1L))
        assertEquals("250", formatThousands(250L))
        assertEquals("999", formatThousands(999L))
    }

    @Test
    fun atAndAboveThousand_insertsCommas() {
        assertEquals("1,000", formatThousands(1_000L))
        assertEquals("5,000", formatThousands(5_000L))
        assertEquals("12,345", formatThousands(12_345L))
        assertEquals("100,000", formatThousands(100_000L))
    }

    @Test
    fun atAndAboveMillion_insertsBothCommas() {
        assertEquals("1,000,000", formatThousands(1_000_000L))
        assertEquals("1,234,567", formatThousands(1_234_567L))
    }

    @Test
    fun negativeAmounts_keepLeadingMinus() {
        assertEquals("-1", formatThousands(-1L))
        assertEquals("-999", formatThousands(-999L))
        assertEquals("-1,000", formatThousands(-1_000L))
        assertEquals("-12,345", formatThousands(-12_345L))
        assertEquals("-1,234,567", formatThousands(-1_234_567L))
    }
}

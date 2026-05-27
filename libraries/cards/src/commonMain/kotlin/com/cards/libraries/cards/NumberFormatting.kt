package com.dangerfield.cards.libraries.cards

/**
 * Formats a numeric amount with thousands separators ("5000" → "5,000",
 * "1234567" → "1,234,567", "-12345" → "-12,345").
 *
 * KMP-friendly — no `Locale` ergonomics, no `String.format`. Comma is the
 * hardcoded separator since the app is English-only at V1; when localisation
 * lands the formatter can take a `Locale` parameter and cascade to
 * `NumberFormat` per platform.
 *
 * Use everywhere a count renders to the user — chip amounts, XP totals,
 * hand counts, anything where the raw `${value}` reads as "ninetyfivethousand?"
 * The comma collapses that parse-pause to nothing. Tight surfaces that need
 * `"12k"` / `"1.2M"` shorthand should reach for
 * [com.dangerfield.cards.libraries.ui.components.formatCompactChips] instead.
 */
fun formatThousands(value: Long): String {
    if (value > -1_000L && value < 1_000L) return value.toString()
    val negative = value < 0L
    val digits = (if (negative) -value else value).toString()
    val out = StringBuilder()
    var count = 0
    for (i in digits.lastIndex downTo 0) {
        out.append(digits[i])
        count++
        if (count == 3 && i > 0) {
            out.append(',')
            count = 0
        }
    }
    if (negative) out.append('-')
    return out.reverse().toString()
}

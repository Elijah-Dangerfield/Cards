package com.dangerfield.cards.libraries.identity.profile

/**
 * Shared rules for an acceptable user display name, so onboarding and
 * edit-profile validate identically. Length is measured on the trimmed
 * name; emoji are rejected outright.
 *
 * The server stays authoritative — it enforces the same minimum length and
 * emoji rejection — but validating client-side gives immediate feedback and
 * keeps an obviously-bad name from costing a round-trip.
 */
object DisplayNameRules {
    /** Minimum trimmed length — rules out 1–2 char throwaways. */
    const val MIN_LENGTH: Int = 3

    /** Client-side max. Stricter than the server's cap — a UX clamp so the
     *  field can't grow unbounded. */
    const val MAX_LENGTH: Int = 16

    /**
     * True when [raw] is an acceptable display name: within length bounds
     * (after trimming) and free of emoji.
     */
    fun isValid(raw: String): Boolean {
        val trimmed = raw.trim()
        return trimmed.length in MIN_LENGTH..MAX_LENGTH && !containsEmoji(trimmed)
    }

    /**
     * True if [text] contains any emoji code point. Walks the string by
     * Unicode code point (combining surrogate pairs by hand) and tests each
     * against the common emoji blocks — pure code-point math so it behaves
     * the same on every Kotlin target rather than leaning on platform regex
     * or Unicode-property class support.
     */
    fun containsEmoji(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val codePoint = if (
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            ) {
                val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                i += 2
                cp
            } else {
                i += 1
                c.code
            }
            if (isEmojiCodePoint(codePoint)) return true
        }
        return false
    }

    private fun isEmojiCodePoint(cp: Int): Boolean = when {
        cp in 0x1F000..0x1FAFF -> true       // emoticons, pictographs, transport, flags, supplemental
        cp in 0x2600..0x27BF -> true         // misc symbols + dingbats (☀ ✂ ✅ …)
        cp in 0x2300..0x23FF -> true         // misc technical (⌚ ⏰ ⏳ …)
        cp in 0x2B00..0x2BFF -> true         // misc symbols & arrows (⭐ ⬅ …)
        cp in 0x2190..0x21FF -> true         // arrows (↔ ↩ …) — commonly emoji-presented
        cp in 0xFE00..0xFE0F -> true         // variation selectors (emoji vs text presentation)
        cp == 0x200D -> true                 // zero-width joiner (emoji sequences)
        cp == 0x20E3 -> true                 // combining enclosing keycap (1️⃣ …)
        cp == 0x2122 || cp == 0x2139 -> true // ™ ℹ
        cp == 0x24C2 -> true                 // Ⓜ
        cp == 0x3030 || cp == 0x303D -> true // 〰 〽
        cp == 0x3297 || cp == 0x3299 -> true // ㊗ ㊙
        else -> false
    }
}

package com.dangerfield.cards.server.http

import kotlin.test.Test
import kotlin.test.assertEquals

class LocaleMatchTest {

    private val sample = mapOf(
        "en" to "Hello",
        "es" to "Hola",
        "fr" to "Bonjour",
    )

    @Test
    fun exactMatchReturned() {
        assertEquals("Hola", pickLocalized(sample, listOf("es")))
    }

    @Test
    fun caseInsensitiveMatch() {
        assertEquals("Hola", pickLocalized(sample, listOf("ES")))
        assertEquals("Hola", pickLocalized(sample, listOf("Es-MX")))
    }

    @Test
    fun stripsSubtagToFindParent() {
        // catalog has `en`, request asks for `en-US` → falls back to `en`.
        assertEquals("Hello", pickLocalized(sample, listOf("en-US")))
    }

    @Test
    fun walksMultiSubtagChain() {
        val map = mapOf("zh" to "你好")
        assertEquals("你好", pickLocalized(map, listOf("zh-Hant-TW")))
    }

    @Test
    fun firstPreferenceWins() {
        // both `de` and `es` are in the preferences, but `de` is first.
        // Catalog only has `es`. Since `de` doesn't match anything, falls
        // through to `es` on the next preference.
        assertEquals("Hola", pickLocalized(sample, listOf("de", "es")))
    }

    @Test
    fun fallsBackToEnglishWhenNoPreferenceMatches() {
        assertEquals("Hello", pickLocalized(sample, listOf("ja", "ko")))
    }

    @Test
    fun fallsBackToFirstValueWhenNoEnglish() {
        val noEnglish = mapOf("ja" to "こんにちは", "ko" to "안녕하세요")
        // No `en`, no preference match — picks an arbitrary value (the first
        // in iteration order).
        val result = pickLocalized(noEnglish, listOf("de"))
        assertEquals(true, result in noEnglish.values)
    }

    @Test
    fun emptyMapReturnsDefault() {
        assertEquals("fallback", pickLocalized(emptyMap(), listOf("en"), default = "fallback"))
    }
}

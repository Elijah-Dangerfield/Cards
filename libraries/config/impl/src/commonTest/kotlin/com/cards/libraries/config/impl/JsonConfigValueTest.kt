package com.dangerfield.cards.libraries.config.impl

import com.dangerfield.cards.libraries.config.AppConfigMap
import com.dangerfield.cards.libraries.config.JsonConfigValue
import com.dangerfield.cards.libraries.config.impl.model.BasicMapAppConfig
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [JsonConfigValue] — the structured (non-scalar) configured value. The
 * load-bearing guarantees: it deserializes a nested config subtree into a typed
 * model, and **never lets a bad remote value reach the caller** — a missing path
 * or an undecodable payload falls back to the bundled default. The level ladder
 * and future loot tables ride this, so a regression here would silently hand the
 * app a wrong (or crash-on-null) config model.
 */
class JsonConfigValueTest {

    @Serializable
    data class Ladder(val xpPerLevel: List<Int>, val label: String = "default")

    private class LadderConfig(
        appConfigMap: AppConfigMap,
        override val default: Ladder,
    ) : JsonConfigValue<Ladder>(appConfigMap, Ladder.serializer()) {
        override val name = "Level ladder"
        override val path = "progression.ladder"
    }

    private val fallback = Ladder(xpPerLevel = listOf(1), label = "fallback")

    private fun configOf(map: Map<String, *>) = LadderConfig(BasicMapAppConfig(map), fallback)

    @Test
    fun resolvesNestedObjectIntoTypedModel() {
        val config = configOf(
            mapOf(
                "progression" to mapOf(
                    "ladder" to mapOf(
                        "xpPerLevel" to listOf(100, 400, 900),
                        "label" to "season1",
                    ),
                ),
            ),
        )

        assertEquals(Ladder(xpPerLevel = listOf(100, 400, 900), label = "season1"), config.value)
    }

    @Test
    fun missingPath_fallsBackToDefault() {
        val config = configOf(mapOf("progression" to mapOf("other" to 1)))

        assertEquals(fallback, config.value)
    }

    @Test
    fun emptyConfig_fallsBackToDefault() {
        val config = configOf(emptyMap<String, Any>())

        assertEquals(fallback, config.value)
    }

    @Test
    fun undecodablePayload_fallsBackToDefaultRatherThanThrowing() {
        // A scalar where an object is expected — decode must fail soft.
        val config = configOf(
            mapOf("progression" to mapOf("ladder" to "totally-not-a-ladder")),
        )

        assertEquals(fallback, config.value)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val config = configOf(
            mapOf(
                "progression" to mapOf(
                    "ladder" to mapOf(
                        "xpPerLevel" to listOf(50),
                        "label" to "x",
                        "futureKnobTheClientDoesntKnow" to true,
                    ),
                ),
            ),
        )

        assertEquals(Ladder(xpPerLevel = listOf(50), label = "x"), config.value)
    }

    @Test
    fun appliesModelDefaultForOmittedOptionalField() {
        val config = configOf(
            mapOf(
                "progression" to mapOf(
                    "ladder" to mapOf("xpPerLevel" to listOf(7, 8)),
                ),
            ),
        )

        assertEquals(Ladder(xpPerLevel = listOf(7, 8), label = "default"), config.value)
    }
}

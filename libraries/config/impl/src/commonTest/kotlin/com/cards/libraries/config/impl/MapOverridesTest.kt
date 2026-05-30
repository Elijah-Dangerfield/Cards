package com.dangerfield.cards.libraries.config.impl

import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.config.impl.model.BasicMapAppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins `MapOverrides.applyOverrides` — the path-aware merge that
 * threads QA-menu overrides into a freshly-resolved server config
 * map. Used by `OfflineFirstAppConfigRepository` on every cache
 * read, so any regression here silently changes what the app sees
 * for every `ConfiguredValue<T>`.
 *
 * The interesting corners are (1) nested dot-paths creating the
 * branch chain when no value lives there yet, (2) overrides on
 * an existing nested map *preserving sibling keys*, and (3) the
 * defensive copy of inner maps — JSON-deserialized child maps come
 * back as read-only on Kotlin/Native, so a direct `put` would
 * crash without the copy-on-write step in `setValueRecursive`.
 */
class MapOverridesTest {

    @Test
    fun emptyOverrides_returnsReceiverUnchanged() {
        val base = BasicMapAppConfig(mapOf("feature.x" to true))

        val result = base.applyOverrides(emptyList())

        assertSame(
            base,
            result,
            "no overrides → no copy; the receiver is returned as-is",
        )
    }

    @Test
    fun singleTopLevelOverride_appliesAndOtherKeysSurvive() {
        val base = BasicMapAppConfig(mapOf("a" to 1, "b" to 2))

        val result = base.applyOverrides(
            overrides(ConfigOverride(path = "a", value = 99 as Any)),
        )

        assertEquals(99, result.map["a"])
        assertEquals(2, result.map["b"], "unrelated top-level key survives")
    }

    @Test
    fun nestedPath_intoEmptyParent_createsTheBranchChain() {
        val base = BasicMapAppConfig(emptyMap<String, Any>())

        val result = base.applyOverrides(
            overrides(ConfigOverride(path = "feature.flags.enabled", value = true as Any)),
        )

        val feature = result.map["feature"] as Map<*, *>
        val flags = feature["flags"] as Map<*, *>
        assertEquals(true, flags["enabled"])
    }

    @Test
    fun nestedPath_intoExistingMap_preservesSiblings() {
        val base = BasicMapAppConfig(
            mapOf(
                "feature" to mapOf(
                    "flags" to mapOf(
                        "enabled" to false,
                        "sibling" to "keepMe",
                    ),
                ),
            ),
        )

        val result = base.applyOverrides(
            overrides(ConfigOverride(path = "feature.flags.enabled", value = true as Any)),
        )

        val flags = (result.map["feature"] as Map<*, *>)["flags"] as Map<*, *>
        assertEquals(true, flags["enabled"], "override applied")
        assertEquals("keepMe", flags["sibling"], "sibling key survives the copy-on-write")
    }

    @Test
    fun nestedOverride_overReadOnlyInnerMap_doesNotThrow() {
        // Defensive against the Kotlin/Native HashMap issue: JSON-deserialized
        // inner maps come back read-only on Native, so a naive put() into them
        // crashes. The impl handles this by copying the child into a fresh
        // mutable map before recursing. `Map.of`-style read-only maps are the
        // closest cross-target stand-in for the K/Native shape.
        val readOnlyChild: Map<String, Any?> = mapOf("flag" to false)
        val base = BasicMapAppConfig(mapOf("feature" to readOnlyChild))

        val result = base.applyOverrides(
            overrides(ConfigOverride(path = "feature.flag", value = true as Any)),
        )

        val feature = result.map["feature"] as Map<*, *>
        assertEquals(true, feature["flag"])
        // Receiver's inner map must remain untouched — the override path
        // copies into a fresh mutable rather than mutating in place.
        assertEquals(false, readOnlyChild["flag"], "the original child map is not mutated")
    }

    @Test
    fun multipleOverrides_lastWriteWinsAtSamePath() {
        val base = BasicMapAppConfig(mapOf("a" to 1))

        val result = base.applyOverrides(
            overrides(
                ConfigOverride(path = "a", value = 10 as Any),
                ConfigOverride(path = "a", value = 99 as Any),
            ),
        )

        assertEquals(99, result.map["a"], "later override clobbers an earlier one at the same path")
    }

    @Test
    fun multipleOverrides_acrossDifferentBranches_allLand() {
        val base = BasicMapAppConfig(emptyMap<String, Any>())

        val result = base.applyOverrides(
            overrides(
                ConfigOverride(path = "feature.a.enabled", value = true as Any),
                ConfigOverride(path = "feature.b.count", value = 7 as Any),
                ConfigOverride(path = "other", value = "x" as Any),
            ),
        )

        val feature = result.map["feature"] as Map<*, *>
        val a = feature["a"] as Map<*, *>
        val b = feature["b"] as Map<*, *>
        assertEquals(true, a["enabled"])
        assertEquals(7, b["count"])
        assertEquals("x", result.map["other"])
        assertTrue(feature.containsKey("a") && feature.containsKey("b"))
    }

    private fun overrides(vararg items: ConfigOverride<Any>): List<ConfigOverride<Any>> =
        items.toList()
}

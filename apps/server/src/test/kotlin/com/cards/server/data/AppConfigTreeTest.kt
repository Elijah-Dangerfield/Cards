package com.dangerfield.cards.server.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure (no-DB) tests for the flat-to-nested config tree assembler. The wire
 * contract is "nested object keyed by path segments" — these pin that dotted
 * paths expand, shared prefixes merge, and depth beyond two segments works.
 */
class AppConfigTreeTest {

    @Test
    fun assemblesNestedTree_fromDottedPaths() {
        val tree = AppConfigTree.assemble(
            mapOf(
                "upgrade.minSupportedVersionCode" to JsonPrimitive(1),
                "upgrade.maintenanceMode" to JsonPrimitive("off"),
                "social.enabled" to JsonPrimitive(false),
            ),
        )

        val upgrade = tree["upgrade"]!!.jsonObject
        assertEquals(1, upgrade["minSupportedVersionCode"]!!.jsonPrimitive.content.toInt())
        assertEquals("off", upgrade["maintenanceMode"]!!.jsonPrimitive.content)
        assertEquals(false, tree["social"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun mergesSubtrees_thatShareAPrefix() {
        val tree = AppConfigTree.assemble(
            mapOf(
                "a.b.c" to JsonPrimitive(1),
                "a.b.d" to JsonPrimitive(2),
                "a.e" to JsonPrimitive(3),
            ),
        )

        val a = tree["a"]!!.jsonObject
        val b = a["b"]!!.jsonObject
        assertEquals(1, b["c"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, b["d"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, a["e"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun emptyMap_producesEmptyObject() {
        assertTrue(AppConfigTree.assemble(emptyMap()) == JsonObject(emptyMap()))
    }
}

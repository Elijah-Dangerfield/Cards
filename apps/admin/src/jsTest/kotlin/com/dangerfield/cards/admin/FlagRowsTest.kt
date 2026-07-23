package com.dangerfield.cards.admin

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagRowsTest {

    private val manifestEntry = ManifestEntryDto(
        path = "social.enabled",
        type = "boolean",
        default = JsonPrimitive(false),
        description = "Social master switch",
    )

    @Test
    fun rowsAreTheUnionOfDbManifestAndResolve() {
        val rows = buildFlagRows(
            flags = listOf(ConfigFlagDto("db.only", JsonPrimitive(1), 0, emptyList())),
            resolvedByPath = mapOf(
                "resolve.only" to ResolvedFlagDto(path = "resolve.only", resolved = JsonPrimitive(2)),
            ),
            manifestByPath = mapOf("social.enabled" to manifestEntry),
        )
        assertEquals(listOf("db.only", "resolve.only", "social.enabled"), rows.map { it.path })
    }

    @Test
    fun codeOnlyFlagIsNotInDbAndFallsBackToBakedDefault() {
        val row = buildFlagRows(
            flags = emptyList(),
            resolvedByPath = emptyMap(),
            manifestByPath = mapOf("social.enabled" to manifestEntry),
        ).single()
        assertFalse(row.inDb)
        assertNull(row.base)
        assertEquals("false", row.resolved.inline())
        assertEquals("boolean", row.type)
    }

    @Test
    fun dbRowOverridesBakedDefault() {
        val row = buildFlagRows(
            flags = listOf(ConfigFlagDto("social.enabled", JsonPrimitive(true), 0, emptyList())),
            resolvedByPath = emptyMap(),
            manifestByPath = mapOf("social.enabled" to manifestEntry),
        ).single()
        assertTrue(row.inDb)
        assertEquals("true", row.base.inline())
        assertEquals("true", row.resolved.inline())
    }

    @Test
    fun resolvePreviewWinsOverEverythingForTheResolvedColumn() {
        val row = buildFlagRows(
            flags = listOf(ConfigFlagDto("social.enabled", JsonPrimitive(false), 0, emptyList())),
            resolvedByPath = mapOf(
                "social.enabled" to ResolvedFlagDto(
                    path = "social.enabled",
                    resolved = JsonPrimitive(true),
                    matchedRule = MatchedRuleDto(id = "r1", priority = 0),
                ),
            ),
            manifestByPath = mapOf("social.enabled" to manifestEntry),
        ).single()
        assertEquals("true", row.resolved.inline())
        assertEquals(0, row.matchedRule?.priority)
    }

    @Test
    fun bakedVersionLabelNamesTheManifestVersion() {
        assertEquals("v0.1.0 (build 1)", bakedVersionLabel(ManifestResponse(1, "0.1.0", emptyList())))
        assertEquals("build 7", bakedVersionLabel(ManifestResponse(7, null, emptyList())))
        assertNull(bakedVersionLabel(null))
    }
}

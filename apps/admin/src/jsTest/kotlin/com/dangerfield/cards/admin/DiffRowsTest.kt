package com.dangerfield.cards.admin

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffRowsTest {

    private fun flag(path: String, value: Boolean, rules: List<ConfigRuleDto> = emptyList()) =
        ConfigFlagDto(path, JsonPrimitive(value), 0, rules)

    private fun manifest(path: String, default: Boolean) =
        ManifestEntryDto(path, "boolean", JsonPrimitive(default))

    @Test
    fun setOnOneSideOnlyDiffers() {
        val row = buildDiffRows(
            aFlags = listOf(flag("social.enabled", true)),
            aManifest = listOf(manifest("social.enabled", false)),
            bFlags = emptyList(),
            bManifest = listOf(manifest("social.enabled", false)),
        ).single()
        assertTrue(row.serverDiffers)
        assertTrue(row.differs)
        assertEquals("true", row.a.serverLabel)
        assertEquals("not set", row.b.serverLabel)
    }

    @Test
    fun sameValuesDoNotDiffer() {
        val row = buildDiffRows(
            aFlags = listOf(flag("social.enabled", false)),
            aManifest = listOf(manifest("social.enabled", false)),
            bFlags = listOf(flag("social.enabled", false)),
            bManifest = listOf(manifest("social.enabled", false)),
        ).single()
        assertFalse(row.differs)
    }

    @Test
    fun ruleDifferencesCount() {
        val rule = ConfigRuleDto(
            id = "r1",
            flagPath = "social.enabled",
            priority = 0,
            value = JsonPrimitive(true),
            conditions = RuleConditions(countries = setOf("US")),
            enabled = true,
        )
        val row = buildDiffRows(
            aFlags = listOf(flag("social.enabled", false, rules = listOf(rule))),
            aManifest = emptyList(),
            bFlags = listOf(flag("social.enabled", false)),
            bManifest = emptyList(),
        ).single()
        assertTrue(row.rulesDiffer)
        assertTrue(row.differs)
        assertFalse(row.serverDiffers)
    }

    @Test
    fun bakedMismatchIsFlaggedButNotADifferenceByItself() {
        val row = buildDiffRows(
            aFlags = emptyList(),
            aManifest = listOf(manifest("social.enabled", false)),
            bFlags = emptyList(),
            bManifest = listOf(manifest("social.enabled", true)),
        ).single()
        assertTrue(row.bakedDiffers)
        assertFalse(row.differs)
    }

    @Test
    fun unionCoversFlagsMissingEverywhereButOneManifest() {
        val rows = buildDiffRows(
            aFlags = listOf(flag("a.only", true)),
            aManifest = emptyList(),
            bFlags = emptyList(),
            bManifest = listOf(manifest("b.only", false)),
        )
        assertEquals(listOf("a.only", "b.only"), rows.map { it.path })
    }
}

package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.ManifestEntry
import com.dangerfield.cards.server.domain.RuleConditions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigValidationTest {

    private fun entry(path: String, type: String, allowed: List<String>? = null) = ManifestEntry(
        path = path,
        type = type,
        default = JsonPrimitive(false),
        description = null,
        allowedValues = allowed?.let { JsonArray(it.map(::JsonPrimitive)) },
    )

    private fun schema(vararg entries: ManifestEntry) = ConfigSchema.from(entries.toList())

    @Test
    fun boolean_rejectsNonBoolean_acceptsBoolean() {
        val s = schema(entry("social.enabled", "boolean"))
        assertNotNull(s.validateValue("social.enabled", JsonPrimitive(6)))
        assertNotNull(s.validateValue("social.enabled", JsonPrimitive("true"))) // quoted string, not a bool
        assertNull(s.validateValue("social.enabled", JsonPrimitive(true)))
    }

    @Test
    fun int_rejectsStringAndDecimal_acceptsInt() {
        val s = schema(entry("upgrade.minSupportedVersionCode", "int"))
        assertNotNull(s.validateValue("upgrade.minSupportedVersionCode", JsonPrimitive("5")))
        assertNull(s.validateValue("upgrade.minSupportedVersionCode", JsonPrimitive(5)))
    }

    @Test
    fun string_enum_enforcesAllowedValues() {
        val s = schema(entry("upgrade.maintenanceMode", "string", listOf("off", "banner", "blocking")))
        assertNull(s.validateValue("upgrade.maintenanceMode", JsonPrimitive("blocking")))
        assertNotNull(s.validateValue("upgrade.maintenanceMode", JsonPrimitive("banana")))
        assertNotNull(s.validateValue("upgrade.maintenanceMode", JsonPrimitive(3))) // not even a string
    }

    @Test
    fun unknownPath_passes_soNewFlagsArentBlocked() {
        val s = schema(entry("social.enabled", "boolean"))
        assertNull(s.validateValue("brand.newFlag", JsonPrimitive("anything")))
    }

    @Test
    fun ruleConditions_boundsAndOrdering() {
        assertNotNull(validateRuleConditions(RuleConditions(rolloutPercent = 150)))
        assertNull(validateRuleConditions(RuleConditions(rolloutPercent = 50)))
        assertNotNull(validateRuleConditions(RuleConditions(minVersionCode = 100, maxVersionCode = 50)))
        assertNotNull(validateRuleConditions(RuleConditions(minAppVersion = "2.0.0", maxAppVersion = "1.0.0")))
        assertNull(validateRuleConditions(RuleConditions(minAppVersion = "1.0.0", maxAppVersion = "2.0.0")))
    }
}

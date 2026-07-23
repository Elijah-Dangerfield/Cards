package com.dangerfield.cards.admin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValidationTest {

    @Test
    fun typedParsing_acceptsMatchingTypes() {
        assertNull(parseTypedValue("boolean", "true").problem)
        assertNull(parseTypedValue("int", "42").problem)
        assertNull(parseTypedValue("long", "300000").problem)
        assertNull(parseTypedValue("double", "1.5").problem)
        assertNull(parseTypedValue("string", "\"off\"").problem)
        assertNull(parseTypedValue("json", "{\"a\":1}").problem)
        assertNull(parseTypedValue(null, "[1,2]").problem)
    }

    @Test
    fun typedParsing_rejectsMismatches() {
        assertNotNull(parseTypedValue("boolean", "6").problem)
        assertNotNull(parseTypedValue("int", "1.5").problem)
        assertNotNull(parseTypedValue("int", "\"banana\"").problem)
        assertNotNull(parseTypedValue("string", "6").problem)
        assertNotNull(parseTypedValue("json", "{not json").problem)
        assertNotNull(parseTypedValue("int", "").problem)
    }

    @Test
    fun semver_comparesByPrecedence() {
        assertTrue(compareSemver("1.0.1", "1.0.2") < 0)
        assertTrue(compareSemver("1.10.0", "1.9.0") > 0)
        assertEquals(0, compareSemver("1.0", "1.0.0"))
    }

    private fun draft(configure: RuleDraft.() -> Unit): RuleDraft = RuleDraft().apply(configure)

    @Test
    fun ruleDraft_valid() {
        val problems = validateRuleDraft(
            draft {
                priority = "1"
                value = "true"
                minAppVersion = "1.0.0"
                maxAppVersion = "2.0.0"
                rolloutPercent = "50"
            },
            flagType = "boolean",
        )
        assertEquals(emptyList(), problems)
    }

    @Test
    fun ruleDraft_catchesEveryMirroredServerRule() {
        val problems = validateRuleDraft(
            draft {
                priority = "x"
                value = "banana"
                minAppVersion = "2.0.0"
                maxAppVersion = "1.0.0"
                minVersionCode = "9"
                maxVersionCode = "3"
                rolloutPercent = "150"
            },
            flagType = "boolean",
        )
        assertTrue(problems.any { "Priority" in it })
        assertTrue(problems.any { it.startsWith("Value:") })
        assertTrue(problems.any { "App version min is above max" in it })
        assertTrue(problems.any { "Build code min is above max" in it })
        assertTrue(problems.any { "Rollout" in it })
    }

    @Test
    fun ruleDraft_rejectsMalformedSemver() {
        val problems = validateRuleDraft(draft { value = "true"; minAppVersion = "banana" }, flagType = "boolean")
        assertTrue(problems.any { "look like" in it })
    }
}

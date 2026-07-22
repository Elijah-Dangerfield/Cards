package com.dangerfield.cards.admin

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RevertTest {

    private fun entry(
        action: String,
        flagPath: String? = "social.enabled",
        before: kotlinx.serialization.json.JsonElement? = null,
        after: kotlinx.serialization.json.JsonElement? = null,
    ) = ConfigAuditDto(
        id = "a1",
        atEpochMs = 0,
        actor = "elijah",
        action = action,
        flagPath = flagPath,
        before = before,
        after = after,
    )

    private val ruleSnapshot = buildJsonObject {
        put("id", "11111111-2222-3333-4444-555555555555")
        put("priority", 2)
        put("value", true)
        putJsonObject("conditions") {
            put("minAppVersion", "1.2.0")
            put("rolloutPercent", 25)
        }
        put("enabled", true)
        put("description", "beta")
    }

    @Test
    fun createFlagRevertsToRemoval() {
        val plan = revertPlanFor(entry("create_flag", after = JsonPrimitive(true)))
        assertIs<RevertPlan.RemoveFlag>(plan)
        assertEquals("social.enabled", plan.path)
    }

    @Test
    fun updateFlagRevertsToBeforeValue() {
        val plan = revertPlanFor(entry("update_flag", before = JsonPrimitive(false), after = JsonPrimitive(true)))
        assertIs<RevertPlan.RestoreFlag>(plan)
        assertEquals("false", plan.value.inline())
        assertNull(plan.caveat)
    }

    @Test
    fun deleteFlagRevertWarnsAboutCascadedRules() {
        val plan = revertPlanFor(entry("delete_flag", before = JsonPrimitive(true)))
        assertIs<RevertPlan.RestoreFlag>(plan)
        assertNotNull(plan.caveat)
        assertTrue("NOT those rules" in plan.caveat!!)
    }

    @Test
    fun createRuleRevertsToDeletionBySnapshotId() {
        val plan = revertPlanFor(entry("create_rule", after = ruleSnapshot))
        assertIs<RevertPlan.RemoveRule>(plan)
        assertEquals("11111111-2222-3333-4444-555555555555", plan.id)
    }

    @Test
    fun deleteRuleRevertsToFullRestore() {
        val plan = revertPlanFor(entry("delete_rule", before = ruleSnapshot))
        assertIs<RevertPlan.RestoreRule>(plan)
        assertEquals(2, plan.request.priority)
        assertEquals("social.enabled", plan.request.flagPath)
        assertEquals("1.2.0", plan.request.conditions.minAppVersion)
        assertEquals(25, plan.request.conditions.rolloutPercent)
    }

    @Test
    fun unknownActionOrMissingSnapshotIsNotRevertible() {
        assertNull(revertPlanFor(entry("upload_manifest")))
        assertNull(revertPlanFor(entry("update_flag", before = null)))
        assertNull(revertPlanFor(entry("delete_rule", before = JsonPrimitive("not a snapshot"))))
    }

    @Test
    fun auditSentencesReadLikeSentences() {
        assertEquals(
            "elijah changed social.enabled false → true",
            auditSentence(entry("update_flag", before = JsonPrimitive(false), after = JsonPrimitive(true))),
        )
        assertEquals(
            "elijah deleted a rule from social.enabled",
            auditSentence(entry("delete_rule", before = ruleSnapshot)),
        )
    }

    @Test
    fun relativeTimeBuckets() {
        val now = 1_000_000_000_000.0
        assertEquals("just now", relativeTime((now - 30_000).toLong(), now))
        assertEquals("5m ago", relativeTime((now - 5 * 60_000).toLong(), now))
        assertEquals("3h ago", relativeTime((now - 3 * 3_600_000).toLong(), now))
        assertEquals("2d ago", relativeTime((now - 2 * 86_400_000).toLong(), now))
    }
}

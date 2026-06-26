package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AppConfigAuditTable
import com.dangerfield.cards.server.db.AppConfigValuesTable
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for the config admin CRUD + audit trail. Each test works on
 * a unique `qa.admin_*` flag path and clears its flag (FK-cascading its rules)
 * AND its audit rows afterward, so the shared `DatabaseTest` Postgres stays at
 * the seeded baseline `DatabaseSchemaTest` asserts.
 */
@OptIn(ExperimentalTime::class)
class PostgresAppConfigAdminRepositoryTest : DatabaseTest() {

    private val repo = PostgresAppConfigAdminRepository(database, Clock.System)

    @Test
    fun upsertFlag_createsThenUpdates_andAuditsBoth() = runTest {
        val path = uniquePath()
        try {
            repo.upsertFlag(path, JsonPrimitive(1), actor = "alice")
            repo.upsertFlag(path, JsonPrimitive(2), actor = "bob")

            val flag = repo.listFlags().single { it.path == path }
            assertEquals(2, flag.value.let { (it as JsonPrimitive).content.toInt() })

            val audit = repo.listAudit(path, limit = 10)
            assertEquals(listOf("update_flag", "create_flag"), audit.map { it.action }) // newest first
            assertEquals("bob", audit.first().actor)
            assertEquals(1, (audit.first().before as JsonPrimitive).content.toInt())
            assertEquals(2, (audit.first().after as JsonPrimitive).content.toInt())
        } finally {
            cleanup(path)
        }
    }

    @Test
    fun upsertRule_requiresExistingFlag() = runTest {
        val missing = uniquePath()
        val result = repo.upsertRule(rule(flagPath = missing), actor = "alice")
        assertNull(result, "a rule for a non-existent flag must be rejected (null)")
    }

    @Test
    fun upsertRule_thenDeleteFlag_cascadesRule_andAudits() = runTest {
        val path = uniquePath()
        try {
            repo.upsertFlag(path, JsonPrimitive("base"), actor = "alice")
            val saved = repo.upsertRule(
                rule(flagPath = path, conditions = RuleConditions(platforms = setOf("ios"))),
                actor = "alice",
            )
            assertTrue(saved != null)

            val withRule = repo.listFlags().single { it.path == path }
            assertEquals(1, withRule.rules.size)
            assertEquals(setOf("ios"), withRule.rules.single().conditions.platforms)

            // Deleting the flag cascades the rule away.
            assertTrue(repo.deleteFlag(path, actor = "alice"))
            assertTrue(repo.listFlags().none { it.path == path })

            val actions = repo.listAudit(path, limit = 10).map { it.action }
            assertEquals(listOf("delete_flag", "create_rule", "create_flag"), actions)
        } finally {
            cleanup(path)
        }
    }

    @Test
    fun deleteRule_removesRule_keepsFlag() = runTest {
        val path = uniquePath()
        val ruleId = UUID.randomUUID()
        try {
            repo.upsertFlag(path, JsonPrimitive("base"), actor = "alice")
            repo.upsertRule(rule(id = ruleId, flagPath = path), actor = "alice")

            assertTrue(repo.deleteRule(ruleId, actor = "alice"))
            assertFalse(repo.deleteRule(ruleId, actor = "alice"), "second delete is a no-op")

            val flag = repo.listFlags().single { it.path == path }
            assertTrue(flag.rules.isEmpty())
        } finally {
            cleanup(path)
        }
    }

    @Test
    fun deleteFlag_returnsFalse_whenAbsent() = runTest {
        assertFalse(repo.deleteFlag(uniquePath(), actor = "alice"))
    }

    private fun uniquePath() = "qa.admin_${System.nanoTime()}"

    private fun rule(
        id: UUID = UUID.randomUUID(),
        flagPath: String,
        conditions: RuleConditions = RuleConditions(),
    ) = TargetingRule(
        id = id,
        flagPath = flagPath,
        priority = 0,
        value = JsonPrimitive("on"),
        conditions = conditions,
        enabled = true,
        description = null,
    )

    private suspend fun cleanup(path: String) = database.transaction {
        AppConfigValuesTable.deleteWhere { AppConfigValuesTable.path eq path } // cascades rules
        AppConfigAuditTable.deleteWhere { AppConfigAuditTable.flagPath eq path }
    }
}

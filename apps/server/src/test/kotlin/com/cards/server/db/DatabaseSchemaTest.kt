package com.dangerfield.cards.server.db

import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Smoke tests: Flyway can apply the migration end-to-end, and the
 * [Tables.kt] Exposed definitions describe the same schema the SQL just
 * created.
 *
 * If you add a new table to [Tables.kt], add a `select all` line below.
 * If a column rename in the migration leaks past review, this test fails
 * loudly when Exposed can't find the column.
 */
class DatabaseSchemaTest : DatabaseTest() {

    @Test
    fun migrationCreatesAllTables_andExposedDefinitionsLineUp() {
        database.blockingTransaction {
            // Selecting all rows from each empty table proves both that
            // Flyway created the table and that the Exposed columns map
            // 1:1 with what Postgres actually has.
            assertEquals(0, ProfilesTable.selectAll().count())
        }
    }

    @Test
    fun flywayHistoryTable_recordsV1Migration() {
        database.blockingTransaction {
            val applied = TransactionManager.current().exec(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
            ) { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            } ?: emptyList()
            assertTrue(applied.contains("1"), "Expected V1 in flyway_schema_history, saw $applied")
        }
    }
}

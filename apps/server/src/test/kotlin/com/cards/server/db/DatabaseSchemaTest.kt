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
            // 1:1 with what Postgres actually has. `products` is seeded by
            // V5 so we just assert the read succeeds — non-empty proves the
            // seed ran AND the Exposed projection lines up with the schema.
            assertEquals(0, ProfilesTable.selectAll().count())
            assertTrue(
                ProductsTable.selectAll().count() > 0L,
                "V5 should have seeded the products table",
            )
            // V6 wallet tables are created empty — `findOrCreate` in the
            // repo lazy-seeds the row on first read, not via the migration.
            assertEquals(0, WalletsTable.selectAll().count())
            assertEquals(0, WalletEventsTable.selectAll().count())
            assertEquals(0, UserMessagesTable.selectAll().count())
            assertEquals(0, RoomSessionsTable.selectAll().count())
            assertEquals(0, RoomsTable.selectAll().count())
            assertEquals(0, RoomMembersTable.selectAll().count())
            assertEquals(0, TableSessionsTable.selectAll().count())
            assertEquals(0, FriendRelationsTable.selectAll().count())
            assertEquals(0, RecentlyPlayedWithTable.selectAll().count())
            assertEquals(0, UserPlayStyleAggregateTable.selectAll().count())
            assertEquals(0, PlayStyleEventsTable.selectAll().count())
            assertEquals(0, UserPlayerStatsTable.selectAll().count())
            assertEquals(0, PlayerStatEventsTable.selectAll().count())
            // V75 seeds the parity set the old in-memory source returned
            // (upgrade.* ×3 + social.enabled). Non-empty proves the seed ran
            // AND the Exposed projection lines up with the JSONB schema.
            assertEquals(4, AppConfigValuesTable.selectAll().count())
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

package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AppConfigManifestTable
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.domain.ManifestEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class PostgresAppConfigManifestRepositoryTest : DatabaseTest() {

    private val repo = PostgresAppConfigManifestRepository(database, Clock.System)

    @Test
    fun upsertReplacesVersion_andListsNewestFirst() = runTest {
        val v1 = 900_001
        val v2 = 900_002
        try {
            repo.upsertManifest(v1, "9.0.1", listOf(entry("social.enabled", "boolean", false)))
            // Re-uploading a version replaces its rows rather than appending.
            repo.upsertManifest(
                v1,
                "9.0.1",
                listOf(
                    entry("social.enabled", "boolean", false),
                    entry("upgrade.maintenanceMode", "string", "off"),
                ),
            )
            repo.upsertManifest(v2, "9.0.2", listOf(entry("social.enabled", "boolean", true)))

            val v1Entries = repo.getManifest(v1)
            assertEquals(2, v1Entries.size)

            val versions = repo.listVersions()
            assertEquals(listOf(v2, v1), versions.map { it.versionCode })
            assertEquals(2, versions.first { it.versionCode == v1 }.flagCount)

            // getManifest(null) returns the latest captured version.
            assertEquals(JsonPrimitive(true), repo.getManifest(null).single { it.path == "social.enabled" }.default)
        } finally {
            cleanup(v1, v2)
        }
    }

    private fun entry(path: String, type: String, default: Any) = ManifestEntry(
        path = path,
        type = type,
        default = if (default is Boolean) JsonPrimitive(default) else JsonPrimitive(default.toString()),
        description = null,
        allowedValues = null,
    )

    private suspend fun cleanup(vararg versions: Int) = database.transaction {
        versions.forEach { v -> AppConfigManifestTable.deleteWhere { versionCode eq v } }
    }
}

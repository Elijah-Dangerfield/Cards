package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AppConfigValuesTable
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests over the V75-seeded `app_config_values`. Two things matter
 * here: the flat rows assemble into the nested tree the client expects, and a
 * row edit becomes visible to readers once the short in-process cache expires
 * (the "flip a flag, no redeploy" promise).
 *
 * Tests only ever touch throwaway `qa.*` paths and clean them up, so the shared
 * `DatabaseTest` Postgres (which `DatabaseSchemaTest` asserts holds exactly the
 * 4 seeded rows) stays pristine.
 */
@OptIn(ExperimentalTime::class)
class PostgresAppConfigSourceTest : DatabaseTest() {

    private class MutableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private val context = ClientContext(
        platform = ClientContext.Platform.Android,
        appVersion = "1.0.0",
        buildNumber = 1,
        preferredLocales = listOf("en"),
        countryCode = null,
    )

    @Test
    fun read_assemblesSeededTree_nestedByPath() = runTest {
        val source = PostgresAppConfigSource(database, Clock.System)

        val tree = source.read(context, userId = null)

        val upgrade = tree.getValue("upgrade").jsonObject
        assertEquals(1, upgrade.getValue("minSupportedVersionCode").jsonPrimitive.content.toInt())
        assertEquals("off", upgrade.getValue("maintenanceMode").jsonPrimitive.content)
        assertEquals(
            false,
            tree.getValue("social").jsonObject.getValue("enabled").jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun read_reflectsEditedValue_onceCacheExpires() = runTest {
        val path = "qa.flipFlag_${System.nanoTime()}"
        val leaf = path.substringAfter('.')
        val clock = MutableClock(Instant.fromEpochMilliseconds(1_000_000))
        val source = PostgresAppConfigSource(database, clock)
        try {
            upsert(path, "true")

            // First read loads + caches `true`.
            assertEquals(true, source.flag(leaf))

            // Edit the row, then read again within the 30s TTL: still cached `true`.
            setValue(path, "false")
            assertEquals(true, source.flag(leaf), "edit must not show until the cache TTL elapses")

            // Advance past the TTL → the edit is now live.
            clock.instant = clock.instant.plus(31.seconds)
            assertEquals(false, source.flag(leaf), "edit must show after the cache TTL elapses")
        } finally {
            delete(path)
        }
    }

    private suspend fun PostgresAppConfigSource.flag(leaf: String): Boolean =
        read(context, null).getValue("qa").jsonObject.getValue(leaf).jsonPrimitive.content.toBoolean()

    private suspend fun upsert(path: String, valueJson: String) = database.transaction {
        AppConfigValuesTable.insert {
            it[AppConfigValuesTable.path] = path
            it[valueJsonb] = valueJson
            it[updatedAt] = java.time.Instant.now()
        }
    }

    private suspend fun setValue(path: String, valueJson: String) = database.transaction {
        AppConfigValuesTable.update({ AppConfigValuesTable.path eq path }) {
            it[valueJsonb] = valueJson
            it[updatedAt] = java.time.Instant.now()
        }
    }

    private suspend fun delete(path: String) = database.transaction {
        AppConfigValuesTable.deleteWhere { AppConfigValuesTable.path eq path }
    }
}

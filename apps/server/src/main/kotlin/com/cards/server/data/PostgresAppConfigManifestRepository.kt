package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AppConfigManifestTable
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AppConfigManifestRepository
import com.dangerfield.cards.server.domain.ManifestEntry
import com.dangerfield.cards.server.domain.ManifestVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.selectAll
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Postgres store for the per-version in-code config registry. Uploading a
 * version replaces that version's rows in one transaction (idempotent re-runs),
 * so the manifest always reflects the latest export for a build.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresAppConfigManifestRepository(
    private val database: Database,
    private val clock: Clock,
) : AppConfigManifestRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun upsertManifest(
        versionCode: Int,
        appVersion: String?,
        entries: List<ManifestEntry>,
    ): Int = database.transaction {
        AppConfigManifestTable.deleteWhere { AppConfigManifestTable.versionCode eq versionCode }
        val now = nowJava()
        entries.forEach { entry ->
            AppConfigManifestTable.insert {
                it[AppConfigManifestTable.versionCode] = versionCode
                it[AppConfigManifestTable.appVersion] = appVersion
                it[path] = entry.path
                it[type] = entry.type
                it[defaultJsonb] = json.encodeToString(JsonElement.serializer(), entry.default)
                it[description] = entry.description
                it[allowedValuesJsonb] =
                    entry.allowedValues?.let { v -> json.encodeToString(JsonElement.serializer(), v) }
                it[capturedAt] = now
            }
        }
        entries.size
    }

    override suspend fun listVersions(): List<ManifestVersion> = database.transaction {
        AppConfigManifestTable
            .selectAll()
            .orderBy(AppConfigManifestTable.versionCode to SortOrder.DESC)
            .groupBy { it[AppConfigManifestTable.versionCode] }
            .map { (versionCode, rows) ->
                val first = rows.first()
                ManifestVersion(
                    versionCode = versionCode,
                    appVersion = first[AppConfigManifestTable.appVersion],
                    capturedAtEpochMs = first[AppConfigManifestTable.capturedAt].toEpochMilli(),
                    flagCount = rows.size,
                )
            }
            .sortedByDescending { it.versionCode }
    }

    override suspend fun getManifest(versionCode: Int?): List<ManifestEntry> = database.transaction {
        val target = versionCode ?: latestVersionCode() ?: return@transaction emptyList()
        AppConfigManifestTable
            .selectAll()
            .where { AppConfigManifestTable.versionCode eq target }
            .orderBy(AppConfigManifestTable.path to SortOrder.ASC)
            .map { row ->
                ManifestEntry(
                    path = row[AppConfigManifestTable.path],
                    type = row[AppConfigManifestTable.type],
                    default = json.parseToJsonElement(row[AppConfigManifestTable.defaultJsonb]),
                    description = row[AppConfigManifestTable.description],
                    allowedValues = row[AppConfigManifestTable.allowedValuesJsonb]
                        ?.let { json.parseToJsonElement(it) },
                )
            }
    }

    private fun latestVersionCode(): Int? {
        val maxCol = AppConfigManifestTable.versionCode.max()
        return AppConfigManifestTable
            .select(maxCol)
            .firstOrNull()
            ?.get(maxCol)
    }

    private fun nowJava(): java.time.Instant =
        java.time.Instant.ofEpochMilli(clock.now().toEpochMilliseconds())
}

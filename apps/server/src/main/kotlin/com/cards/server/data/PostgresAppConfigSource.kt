package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AppConfigRulesTable
import com.dangerfield.cards.server.db.AppConfigValuesTable
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Postgres-backed app config. Reads the base `path -> value` rows from
 * `app_config_values` plus their per-flag targeting rules from
 * `app_config_rules`, resolves each flag against the calling client (see
 * [AppConfigTargetingEngine]), and assembles the nested override tree the
 * client merges over its defaults.
 *
 * Editing a row (Supabase table editor, or the local admin UI) flips the flag
 * with **no redeploy** — live on the next client config refresh, throttled only
 * by the short in-process [cacheTtl] below.
 *
 * The cache exists because config is read on every client session boundary but
 * changes rarely; a 30-second TTL keeps Postgres off the hot path while still
 * making an admin edit visible within seconds. An empty store is a legitimate
 * state — it just means "use client defaults".
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresAppConfigSource(
    private val database: Database,
    private val clock: Clock,
) : AppConfigSource {

    private val json = Json { ignoreUnknownKeys = true }
    private val engine = AppConfigTargetingEngine()

    private val cacheTtl = 30.seconds
    private val cacheMutex = Mutex()
    private var cache: Snapshot? = null

    override suspend fun read(context: ClientContext, userId: UserId?): JsonObject {
        val snapshot = snapshot()
        // Resolve every flag that has a base value OR at least one rule. A
        // path with only rules (no base) resolves to null when nothing matches
        // and is omitted, so the client keeps its own default.
        val paths = snapshot.baseValues.keys + snapshot.rulesByPath.keys
        val resolved = buildMap<String, JsonElement> {
            for (path in paths) {
                val value = engine.resolve(
                    rules = snapshot.rulesByPath[path].orEmpty(),
                    base = snapshot.baseValues[path],
                    context = context,
                    userId = userId,
                    flagPath = path,
                )
                if (value != null) put(path, value)
            }
        }
        return AppConfigTree.assemble(resolved)
    }

    /** Cached view of the config store, refreshed past [cacheTtl]. */
    private suspend fun snapshot(): Snapshot = cacheMutex.withLock {
        val now = clock.now()
        val current = cache
        if (current != null && now - current.loadedAt < cacheTtl) return current
        val fresh = load(now)
        cache = fresh
        fresh
    }

    private suspend fun load(now: Instant): Snapshot = database.transaction {
        val baseValues: Map<String, JsonElement> = AppConfigValuesTable
            .selectAll()
            .associate { row ->
                row[AppConfigValuesTable.path] to
                    json.parseToJsonElement(row[AppConfigValuesTable.valueJsonb])
            }
        val rulesByPath: Map<String, List<TargetingRule>> = AppConfigRulesTable
            .selectAll()
            .orderBy(AppConfigRulesTable.priority to SortOrder.ASC)
            .map { row ->
                TargetingRule(
                    id = row[AppConfigRulesTable.id],
                    flagPath = row[AppConfigRulesTable.flagPath],
                    priority = row[AppConfigRulesTable.priority],
                    value = json.parseToJsonElement(row[AppConfigRulesTable.valueJsonb]),
                    conditions = json.decodeFromString(
                        RuleConditions.serializer(),
                        row[AppConfigRulesTable.conditionsJsonb],
                    ),
                    enabled = row[AppConfigRulesTable.enabled],
                    description = row[AppConfigRulesTable.description],
                )
            }
            .groupBy { it.flagPath }
        Snapshot(baseValues = baseValues, rulesByPath = rulesByPath, loadedAt = now)
    }

    private data class Snapshot(
        val baseValues: Map<String, JsonElement>,
        val rulesByPath: Map<String, List<TargetingRule>>,
        val loadedAt: Instant,
    )
}

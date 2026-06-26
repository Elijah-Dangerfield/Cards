package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AppConfigValuesTable
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AppConfigSource
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.http.ClientContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.selectAll
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Postgres-backed app config. Reads the flat `path -> value` rows from
 * `app_config_values` and assembles the nested override tree the client merges
 * over its defaults.
 *
 * Editing a row (Supabase table editor, or the local admin UI) flips the flag
 * with **no redeploy** — live on the next client config refresh, throttled only
 * by the short in-process [cacheTtl] below.
 *
 * The cache exists because config is read on every client session boundary but
 * changes rarely; a 30-second TTL keeps Postgres off the hot path while still
 * making an admin edit visible within seconds. An empty table is a legitimate
 * state — it just means "use client defaults".
 *
 * [context] and [userId] aren't used yet — per-flag targeting + staged rollouts
 * (which read them) land in V76. The signature is in place so that's a
 * source-internal change, not a contract change.
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

    private val cacheTtl = 30.seconds
    private val cacheMutex = Mutex()
    private var cache: Snapshot? = null

    override suspend fun read(context: ClientContext, userId: UserId?): JsonObject =
        AppConfigTree.assemble(snapshot().baseValues)

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
        Snapshot(baseValues = baseValues, loadedAt = now)
    }

    private data class Snapshot(
        val baseValues: Map<String, JsonElement>,
        val loadedAt: Instant,
    )
}

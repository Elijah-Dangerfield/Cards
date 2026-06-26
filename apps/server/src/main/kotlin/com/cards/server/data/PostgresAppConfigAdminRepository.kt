package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AppConfigAuditTable
import com.dangerfield.cards.server.db.AppConfigRulesTable
import com.dangerfield.cards.server.db.AppConfigValuesTable
import com.dangerfield.cards.server.db.Database
import com.dangerfield.cards.server.di.ServerScope
import com.dangerfield.cards.server.domain.AppConfigAdminRepository
import com.dangerfield.cards.server.domain.ConfigAuditRecord
import com.dangerfield.cards.server.domain.ConfigFlagRecord
import com.dangerfield.cards.server.domain.RuleConditions
import com.dangerfield.cards.server.domain.TargetingRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.tatarka.inject.annotations.Inject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Postgres CRUD over the raw config rows, plus the audit trail. Every mutation
 * runs in a single transaction with its audit insert, so the log can never
 * diverge from the data: a failed write leaves neither.
 *
 * Uncached on purpose — the admin UI wants to see exactly what's stored, and
 * the client-facing read path ([PostgresAppConfigSource]) has its own cache.
 */
@SingleIn(ServerScope::class)
@ContributesBinding(ServerScope::class)
@Inject
@OptIn(ExperimentalTime::class)
class PostgresAppConfigAdminRepository(
    private val database: Database,
    private val clock: Clock,
) : AppConfigAdminRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listFlags(): List<ConfigFlagRecord> = database.transaction {
        val rulesByPath = AppConfigRulesTable
            .selectAll()
            .orderBy(AppConfigRulesTable.priority to SortOrder.ASC)
            .map { it.toRule() }
            .groupBy { it.flagPath }
        AppConfigValuesTable
            .selectAll()
            .orderBy(AppConfigValuesTable.path to SortOrder.ASC)
            .map { row ->
                val path = row[AppConfigValuesTable.path]
                ConfigFlagRecord(
                    path = path,
                    value = json.parseToJsonElement(row[AppConfigValuesTable.valueJsonb]),
                    updatedAtEpochMs = row[AppConfigValuesTable.updatedAt].toEpochMilli(),
                    rules = rulesByPath[path].orEmpty(),
                )
            }
    }

    override suspend fun upsertFlag(path: String, value: JsonElement, actor: String): ConfigFlagRecord =
        database.transaction {
            val before = AppConfigValuesTable
                .selectAll()
                .where { AppConfigValuesTable.path eq path }
                .singleOrNull()
                ?.let { json.parseToJsonElement(it[AppConfigValuesTable.valueJsonb]) }

            val encoded = json.encodeToString(JsonElement.serializer(), value)
            val now = nowJava()
            val updated = AppConfigValuesTable.update({ AppConfigValuesTable.path eq path }) {
                it[valueJsonb] = encoded
                it[updatedAt] = now
            }
            if (updated == 0) {
                AppConfigValuesTable.insert {
                    it[AppConfigValuesTable.path] = path
                    it[valueJsonb] = encoded
                    it[updatedAt] = now
                }
            }
            audit(
                action = if (before == null) "create_flag" else "update_flag",
                flagPath = path,
                before = before,
                after = value,
                actor = actor,
            )
            val rules = AppConfigRulesTable
                .selectAll()
                .where { AppConfigRulesTable.flagPath eq path }
                .orderBy(AppConfigRulesTable.priority to SortOrder.ASC)
                .map { it.toRule() }
            ConfigFlagRecord(path, value, now.toEpochMilli(), rules)
        }

    override suspend fun deleteFlag(path: String, actor: String): Boolean = database.transaction {
        val before = AppConfigValuesTable
            .selectAll()
            .where { AppConfigValuesTable.path eq path }
            .singleOrNull()
            ?.let { json.parseToJsonElement(it[AppConfigValuesTable.valueJsonb]) }
            ?: return@transaction false
        AppConfigValuesTable.deleteWhere { AppConfigValuesTable.path eq path }
        audit(action = "delete_flag", flagPath = path, before = before, after = null, actor = actor)
        true
    }

    override suspend fun upsertRule(rule: TargetingRule, actor: String): TargetingRule? =
        database.transaction {
            val flagExists = AppConfigValuesTable
                .selectAll()
                .where { AppConfigValuesTable.path eq rule.flagPath }
                .any()
            if (!flagExists) return@transaction null

            val before = AppConfigRulesTable
                .selectAll()
                .where { AppConfigRulesTable.id eq rule.id }
                .singleOrNull()
                ?.let { ruleSnapshot(it.toRule()) }

            val encodedValue = json.encodeToString(JsonElement.serializer(), rule.value)
            val encodedConditions = json.encodeToString(RuleConditions.serializer(), rule.conditions)
            val now = nowJava()
            val updated = AppConfigRulesTable.update({ AppConfigRulesTable.id eq rule.id }) {
                it[flagPath] = rule.flagPath
                it[priority] = rule.priority
                it[valueJsonb] = encodedValue
                it[conditionsJsonb] = encodedConditions
                it[enabled] = rule.enabled
                it[description] = rule.description
                it[updatedAt] = now
            }
            if (updated == 0) {
                AppConfigRulesTable.insert {
                    it[id] = rule.id
                    it[flagPath] = rule.flagPath
                    it[priority] = rule.priority
                    it[valueJsonb] = encodedValue
                    it[conditionsJsonb] = encodedConditions
                    it[enabled] = rule.enabled
                    it[description] = rule.description
                    it[updatedAt] = now
                }
            }
            audit(
                action = if (before == null) "create_rule" else "update_rule",
                flagPath = rule.flagPath,
                before = before,
                after = ruleSnapshot(rule),
                actor = actor,
            )
            rule
        }

    override suspend fun deleteRule(id: UUID, actor: String): Boolean = database.transaction {
        val existing = AppConfigRulesTable
            .selectAll()
            .where { AppConfigRulesTable.id eq id }
            .singleOrNull()
            ?.toRule()
            ?: return@transaction false
        AppConfigRulesTable.deleteWhere { AppConfigRulesTable.id eq id }
        audit(
            action = "delete_rule",
            flagPath = existing.flagPath,
            before = ruleSnapshot(existing),
            after = null,
            actor = actor,
        )
        true
    }

    override suspend fun listAudit(flagPath: String?, limit: Int): List<ConfigAuditRecord> =
        database.transaction {
            val base = AppConfigAuditTable.selectAll()
            val filtered = if (flagPath != null) {
                base.where { AppConfigAuditTable.flagPath eq flagPath }
            } else {
                base
            }
            filtered
                .orderBy(AppConfigAuditTable.at to SortOrder.DESC)
                .limit(limit.coerceIn(1, MAX_AUDIT_LIMIT))
                .map { row ->
                    ConfigAuditRecord(
                        id = row[AppConfigAuditTable.id],
                        atEpochMs = row[AppConfigAuditTable.at].toEpochMilli(),
                        actor = row[AppConfigAuditTable.actor],
                        action = row[AppConfigAuditTable.action],
                        flagPath = row[AppConfigAuditTable.flagPath],
                        before = row[AppConfigAuditTable.beforeJsonb]?.let { json.parseToJsonElement(it) },
                        after = row[AppConfigAuditTable.afterJsonb]?.let { json.parseToJsonElement(it) },
                    )
                }
        }

    /** Write one audit row. Must be called inside the mutating transaction. */
    private fun audit(
        action: String,
        flagPath: String?,
        before: JsonElement?,
        after: JsonElement?,
        actor: String,
    ) {
        AppConfigAuditTable.insert {
            it[id] = UUID.randomUUID()
            it[at] = nowJava()
            it[AppConfigAuditTable.actor] = actor
            it[AppConfigAuditTable.action] = action
            it[AppConfigAuditTable.flagPath] = flagPath
            it[beforeJsonb] = before?.let { e -> json.encodeToString(JsonElement.serializer(), e) }
            it[afterJsonb] = after?.let { e -> json.encodeToString(JsonElement.serializer(), e) }
        }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toRule(): TargetingRule = TargetingRule(
        id = this[AppConfigRulesTable.id],
        flagPath = this[AppConfigRulesTable.flagPath],
        priority = this[AppConfigRulesTable.priority],
        value = json.parseToJsonElement(this[AppConfigRulesTable.valueJsonb]),
        conditions = json.decodeFromString(
            RuleConditions.serializer(),
            this[AppConfigRulesTable.conditionsJsonb],
        ),
        enabled = this[AppConfigRulesTable.enabled],
        description = this[AppConfigRulesTable.description],
    )

    private fun ruleSnapshot(rule: TargetingRule): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(rule.id.toString()))
        put("priority", JsonPrimitive(rule.priority))
        put("value", rule.value)
        put("conditions", json.encodeToJsonElement(RuleConditions.serializer(), rule.conditions))
        put("enabled", JsonPrimitive(rule.enabled))
        put("description", rule.description?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun nowJava(): java.time.Instant =
        java.time.Instant.ofEpochMilli(clock.now().toEpochMilliseconds())

    private companion object {
        const val MAX_AUDIT_LIMIT = 500
    }
}

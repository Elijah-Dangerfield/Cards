package com.dangerfield.cards.server.domain

import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * Read/write surface for the config admin tooling (the local web GUI + any
 * cron/script). Distinct from [AppConfigSource], which is the *resolved*,
 * cached read path the client hits — this is the uncached, authoritative CRUD
 * over the raw `app_config_values` + `app_config_rules` rows, and every
 * mutation appends to the audit log.
 *
 * Edits here become visible to clients on the next [AppConfigSource] cache
 * refresh (its short TTL), not instantly — there's no cross-process bust, by
 * design: config changes are rare and a few seconds of staleness is fine.
 */
interface AppConfigAdminRepository {

    /** Every flag with its base value and rules, ordered by path then priority. */
    suspend fun listFlags(): List<ConfigFlagRecord>

    /** Create or replace a flag's base value. Returns the stored record. */
    suspend fun upsertFlag(path: String, value: JsonElement, actor: String): ConfigFlagRecord

    /** Delete a flag and (FK-cascade) its rules. Returns false if it didn't exist. */
    suspend fun deleteFlag(path: String, actor: String): Boolean

    /**
     * Create or replace a targeting rule. The rule's flag must already exist
     * (FK). Returns null if the flag is missing, else the stored rule.
     */
    suspend fun upsertRule(rule: TargetingRule, actor: String): TargetingRule?

    /** Delete a rule by id. Returns false if it didn't exist. */
    suspend fun deleteRule(id: UUID, actor: String): Boolean

    /** Audit entries, newest first, optionally filtered to one flag. */
    suspend fun listAudit(flagPath: String?, limit: Int): List<ConfigAuditRecord>
}

/** A flag's base value + its rules, as stored (not resolved for any caller). */
data class ConfigFlagRecord(
    val path: String,
    val value: JsonElement,
    val updatedAtEpochMs: Long,
    val rules: List<TargetingRule>,
)

/** One row of the config change audit log. */
data class ConfigAuditRecord(
    val id: UUID,
    val atEpochMs: Long,
    val actor: String,
    val action: String,
    val flagPath: String?,
    val before: JsonElement?,
    val after: JsonElement?,
)

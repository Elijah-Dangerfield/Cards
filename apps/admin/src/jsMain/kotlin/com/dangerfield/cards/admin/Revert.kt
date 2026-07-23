package com.dangerfield.cards.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One-click revert: every audited action has an inverse expressible with the
 * existing flag/rule endpoints, computed purely from the audit entry's
 * before/after snapshots. A revert is itself an ordinary audited mutation, so
 * reverting a revert falls out for free.
 */
internal sealed interface RevertPlan {
    /** Human sentence of what the revert will write, for the confirm sheet. */
    val summary: String

    /** Extra caveat shown alongside the confirm, when the inverse is lossy. */
    val caveat: String? get() = null

    suspend fun execute(api: AdminApi)

    data class RestoreFlag(val path: String, val value: JsonElement, val wasDeleted: Boolean) : RevertPlan {
        override val summary get() = "set $path back to ${value.inline()}"
        override val caveat get() = if (wasDeleted) {
            "Deleting this flag also cascade-deleted its rules; this restores the value but NOT those rules."
        } else {
            null
        }

        override suspend fun execute(api: AdminApi) = api.upsertFlag(path, value)
    }

    data class RemoveFlag(val path: String) : RevertPlan {
        override val summary get() = "remove the server value for $path (back to the baked default)"
        override suspend fun execute(api: AdminApi) = api.deleteFlag(path)
    }

    data class RestoreRule(val id: String, val request: UpsertRuleRequest) : RevertPlan {
        override val summary get() = "restore rule #${request.priority} on ${request.flagPath}"
        override suspend fun execute(api: AdminApi) = api.upsertRule(id, request)
    }

    data class RemoveRule(val id: String, val flagPath: String?) : RevertPlan {
        override val summary get() = "delete the rule that change created${flagPath?.let { " on $it" }.orEmpty()}"
        override suspend fun execute(api: AdminApi) = api.deleteRule(id)
    }
}

/** Mirror of the server's `ruleSnapshot` JSON stored in audit before/after. */
@Serializable
internal data class RuleSnapshot(
    val id: String,
    val priority: Int,
    val value: JsonElement,
    val conditions: RuleConditions = RuleConditions(),
    val enabled: Boolean = true,
    val description: String? = null,
) {
    fun toRequest(flagPath: String): UpsertRuleRequest = UpsertRuleRequest(
        flagPath = flagPath,
        priority = priority,
        value = value,
        conditions = conditions,
        enabled = enabled,
        description = description,
    )
}

private fun JsonElement.asRuleSnapshot(): RuleSnapshot? =
    Catching { adminJson.decodeFromJsonElement(RuleSnapshot.serializer(), this) }.getOrNull()

/**
 * The inverse of an audited action, or null when this entry can't be reverted
 * (unknown action, or a malformed/missing snapshot).
 */
internal fun revertPlanFor(entry: ConfigAuditDto): RevertPlan? = when (entry.action) {
    "create_flag" -> entry.flagPath?.let { RevertPlan.RemoveFlag(it) }

    "update_flag" -> entry.flagPath?.let { path ->
        entry.before?.let { RevertPlan.RestoreFlag(path, it, wasDeleted = false) }
    }

    "delete_flag" -> entry.flagPath?.let { path ->
        entry.before?.let { RevertPlan.RestoreFlag(path, it, wasDeleted = true) }
    }

    "create_rule" -> entry.after?.asRuleSnapshot()?.let { RevertPlan.RemoveRule(it.id, entry.flagPath) }

    // Restoring a deleted rule re-upserts it under its original id. If the
    // flag row vanished since, the server re-seeds it from the manifest — or
    // honestly 409s, which surfaces in the error log.
    "update_rule", "delete_rule" -> entry.flagPath?.let { path ->
        entry.before?.asRuleSnapshot()?.let { RevertPlan.RestoreRule(it.id, it.toRequest(path)) }
    }

    else -> null
}

/** "elijah set social.enabled → false" — one readable line per audit entry. */
internal fun auditSentence(entry: ConfigAuditDto): String {
    val path = entry.flagPath ?: "?"
    return when (entry.action) {
        "create_flag" -> "${entry.actor} set $path → ${entry.after.inline()} (new server value)"
        "update_flag" -> "${entry.actor} changed $path ${entry.before.inline()} → ${entry.after.inline()}"
        "delete_flag" -> "${entry.actor} removed the server value for $path (was ${entry.before.inline()})"
        "create_rule" -> "${entry.actor} added a rule to $path"
        "update_rule" -> "${entry.actor} edited a rule on $path"
        "delete_rule" -> "${entry.actor} deleted a rule from $path"
        else -> "${entry.actor} ${entry.action} $path"
    }
}

/** Compact relative timestamp: "3m ago", "2h ago", "5d ago". */
internal fun relativeTime(epochMs: Long, nowMs: Double = kotlin.js.Date.now()): String {
    val seconds = ((nowMs - epochMs) / 1000).toLong().coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86_400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86_400}d ago"
    }
}

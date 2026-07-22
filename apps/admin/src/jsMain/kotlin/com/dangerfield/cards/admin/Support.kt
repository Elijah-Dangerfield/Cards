package com.dangerfield.cards.admin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** A one-line success/error banner shown under the connection panel. */
internal data class Status(val ok: Boolean, val message: String)

/**
 * A failed write, kept until the operator dismisses it. The transient status
 * line gets overwritten by the next action; this list doesn't — so a rejected
 * write can never silently "snap back" (the reload refreshes server truth, but
 * the attempted value survives here).
 */
internal data class ErrorEntry(
    val time: String,
    val operation: String,
    val attempted: String?,
    val message: String,
)

internal fun nowTimeLabel(): String = js("new Date().toLocaleTimeString()") as String

/**
 * Everything a view needs to make a write: the api, which environment it hits,
 * and the shared safety plumbing. [confirmWrite] is the only mutation path in
 * the console — it puts a before→after confirm sheet in front of the operator,
 * and on failure records a persistent [ErrorEntry].
 *
 * Reloads on both success and failure: a rejected write can still have changed
 * state (or the operator's view of it can be stale), so we always re-fetch so
 * the flag/rule list reflects what the server actually holds.
 */
internal class AdminCtx(
    val api: AdminApi,
    val scope: CoroutineScope,
    val envName: String,
    val isProd: Boolean,
    val setStatus: (Status) -> Unit,
    val reload: () -> Unit,
    private val requestConfirm: (PendingWrite) -> Unit,
    private val reportError: (ErrorEntry) -> Unit,
) {
    fun confirmWrite(
        title: String,
        flagPath: String?,
        before: String,
        after: String,
        success: String,
        warning: String? = null,
        block: suspend () -> Unit,
    ) {
        requestConfirm(
            PendingWrite(
                title = title,
                envName = envName,
                isProd = isProd,
                flagPath = flagPath,
                before = before,
                after = after,
                warning = warning,
                requireEnvTyping = isProd && warning != null,
                onConfirm = {
                    scope.launch {
                        Catching { block() }
                            .onSuccess { setStatus(Status(true, success)) }
                            .onFailure { error ->
                                val message = error.message ?: "Request failed"
                                reportError(ErrorEntry(nowTimeLabel(), title, after, message))
                                setStatus(Status(false, message))
                            }
                        reload()
                    }
                },
            ),
        )
    }
}

@Suppress("TooGenericExceptionCaught")
internal fun parseJsonOrNull(raw: String): JsonElement? =
    Catching { adminJson.parseToJsonElement(raw.trim()) }.getOrNull()

internal fun csvSet(raw: String): Set<String>? =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet().ifEmpty { null }

internal fun randomUuid(): String = js("crypto.randomUUID()") as String

/**
 * A confirm prompt for values that hit every user hard — locking them out or
 * forcing an upgrade. Returns the warning to show, or null when the change is
 * routine. Keyed on (path, value) because the danger is value-specific
 * (`maintenanceMode = "blocking"` is a lockout; `"off"` is harmless).
 */
internal fun dangerousWarning(path: String, value: String): String? {
    val v = value.trim()
    return when {
        path == "upgrade.maintenanceMode" && v == "\"blocking\"" ->
            "This sets maintenance mode to BLOCKING — it locks ALL users out of the app. Continue?"
        path == "upgrade.maintenanceMode" && v == "\"banner\"" ->
            "This shows a maintenance banner to ALL users. Continue?"
        path == "upgrade.minSupportedVersionCode" ->
            "Raising the minimum supported version force-upgrades every user below it. Double-check the number. Continue?"
        else -> null
    }
}

/** Compact, human-readable rendering of a JSON value for tables (no pretty-print). */
internal fun JsonElement?.inline(): String = this?.toString() ?: "—"

/**
 * A targeting rule as a plain-English clause: "app version > 1.0.1 and country
 * in US/CA". The value it sets is shown separately by the caller.
 */
internal fun conditionsSentence(c: RuleConditions): String {
    val parts = buildList {
        c.platforms?.let { add("platform in ${it.joinToString("/")}") }
        appVersionPhrase(c)?.let { add(it) }
        c.minVersionCode?.let { add("build ≥ $it") }
        c.maxVersionCode?.let { add("build ≤ $it") }
        c.countries?.let { add("country in ${it.joinToString("/")}") }
        c.locales?.let { add("locale in ${it.joinToString("/")}") }
        c.userAllow?.let { add("user in allowlist (${it.size})") }
        c.userDeny?.let { add("user not in denylist (${it.size})") }
        c.rolloutPercent?.let { add("rollout $it%") }
    }
    return if (parts.isEmpty()) "everyone" else parts.joinToString(" and ")
}

private fun appVersionPhrase(c: RuleConditions): String? {
    val min = c.minAppVersion?.takeUnless { it.isBlank() }
    val max = c.maxAppVersion?.takeUnless { it.isBlank() }
    val minOp = if (c.minAppVersionInclusive) "≥" else ">"
    val maxOp = if (c.maxAppVersionInclusive) "≤" else "<"
    return when {
        min != null && max != null -> "app version $minOp $min and $maxOp $max"
        min != null -> "app version $minOp $min"
        max != null -> "app version $maxOp $max"
        else -> null
    }
}

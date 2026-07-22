package com.dangerfield.cards.admin

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Client-side checks that mirror the server's `ConfigValidation` so the obvious
 * mistakes are caught before a request is ever sent. The server remains the
 * authority — these exist so the operator sees the problem next to the field
 * instead of as a 400 after saving.
 */

/** A parsed value or the reason it didn't parse. Exactly one side is set. */
internal data class ParsedValue(val element: JsonElement? = null, val problem: String? = null)

internal fun parseTypedValue(type: String?, raw: String): ParsedValue {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ParsedValue(problem = "Value is required")
    val element = parseJsonOrNull(trimmed) ?: return ParsedValue(problem = "Not valid JSON")
    val primitive = element as? JsonPrimitive
    val problem = when (type) {
        "boolean" -> "Must be true or false".takeIf { primitive?.booleanOrNull == null }
        "int", "long" -> "Must be a whole number".takeIf { primitive?.longOrNull == null }
        "double" -> "Must be a number".takeIf { primitive?.doubleOrNull == null }
        "string" -> "Must be a string".takeIf { primitive == null || !primitive.isString }
        else -> null
    }
    return if (problem != null) ParsedValue(problem = problem) else ParsedValue(element = element)
}

private val SEMVER = Regex("""\d+(\.\d+){1,2}""")

internal fun semverProblem(label: String, version: String): String? =
    "$label must look like 1.2.0".takeUnless { version.isBlank() || SEMVER.matches(version.trim()) }

/** SemVer precedence compare on dotted numeric parts (missing parts are 0). */
internal fun compareSemver(a: String, b: String): Int {
    val left = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
    val right = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
    repeat(maxOf(left.size, right.size)) { i ->
        val diff = (left.getOrElse(i) { 0 }).compareTo(right.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

/** Every problem with a rule draft, in display order. Empty means saveable. */
internal fun validateRuleDraft(draft: RuleDraft, flagType: String?): List<String> = buildList {
    if (draft.priority.trim().toIntOrNull() == null) add("Priority must be a whole number")
    parseTypedValue(flagType, draft.value).problem?.let { add("Value: $it") }

    draft.rolloutPercent.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        val pct = raw.toIntOrNull()
        if (pct == null || pct !in 0..100) add("Rollout must be 0-100")
    }

    val minCode = draft.minVersionCode.trim()
    val maxCode = draft.maxVersionCode.trim()
    if (minCode.isNotEmpty() && minCode.toIntOrNull() == null) add("Build code min must be a whole number")
    if (maxCode.isNotEmpty() && maxCode.toIntOrNull() == null) add("Build code max must be a whole number")
    val minCodeInt = minCode.toIntOrNull()
    val maxCodeInt = maxCode.toIntOrNull()
    if (minCodeInt != null && maxCodeInt != null && minCodeInt > maxCodeInt) {
        add("Build code min is above max")
    }

    semverProblem("App version min", draft.minAppVersion)?.let { add(it) }
    semverProblem("App version max", draft.maxAppVersion)?.let { add(it) }
    val minVersion = draft.minAppVersion.trim()
    val maxVersion = draft.maxAppVersion.trim()
    if (minVersion.isNotEmpty() && maxVersion.isNotEmpty() &&
        semverProblem("", minVersion) == null && semverProblem("", maxVersion) == null &&
        compareSemver(minVersion, maxVersion) > 0
    ) {
        add("App version min is above max")
    }
}

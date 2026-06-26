package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.ManifestEntry
import com.dangerfield.cards.server.domain.RuleConditions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Type / allowed-values schema for config values, built from the registry the
 * app shipped (the latest manifest). It stops the admin tool from writing a
 * value the client can't parse — e.g. `social.enabled = 6` or
 * `upgrade.maintenanceMode = "banana"`.
 *
 * Unknown paths pass: a brand-new flag added through the admin tool may legitimately
 * precede the build that declares it, so we can't type-check it yet — we only
 * enforce a type when we actually know one.
 */
class ConfigSchema(private val byPath: Map<String, ManifestEntry>) {

    companion object {
        fun from(entries: List<ManifestEntry>): ConfigSchema = ConfigSchema(entries.associateBy { it.path })
    }

    /** null when the value is acceptable for [path]; otherwise a human-readable reason. */
    fun validateValue(path: String, value: JsonElement): String? {
        val entry = byPath[path] ?: return null
        val primitive = value as? JsonPrimitive

        val typeError = when (entry.type) {
            "boolean" -> if (primitive == null || primitive.isString || primitive.booleanOrNull == null) "expected a boolean" else null
            "int" -> if (primitive == null || primitive.isString || primitive.intOrNull == null) "expected an int" else null
            "long" -> if (primitive == null || primitive.isString || primitive.longOrNull == null) "expected a long" else null
            "double" -> if (primitive == null || primitive.isString || primitive.doubleOrNull == null) "expected a number" else null
            "string" -> if (primitive == null || !primitive.isString) "expected a string" else null
            else -> null // "json" / unknown type → accept any shape
        }
        if (typeError != null) return "$path $typeError, got $value"

        val allowed = (entry.allowedValues as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.toSet()
        val content = primitive?.takeIf { it.isString }?.content
        if (allowed != null && content != null && content !in allowed) {
            return "$path must be one of ${allowed.joinToString()}; got \"$content\""
        }
        return null
    }
}

/**
 * Structural sanity for a rule's conditions, independent of any schema: rollout
 * is a percentage, and the min/max bounds are actually ordered. null when fine.
 */
fun validateRuleConditions(conditions: RuleConditions): String? {
    conditions.rolloutPercent?.let { if (it !in 0..100) return "rolloutPercent must be between 0 and 100" }
    val minCode = conditions.minVersionCode
    val maxCode = conditions.maxVersionCode
    if (minCode != null && maxCode != null && minCode > maxCode) {
        return "minVersionCode must be ≤ maxVersionCode"
    }
    val minVer = conditions.minAppVersion?.takeUnless { it.isBlank() }
    val maxVer = conditions.maxAppVersion?.takeUnless { it.isBlank() }
    if (minVer != null && maxVer != null && SemVer.compare(minVer, maxVer) > 0) {
        return "minAppVersion must be ≤ maxAppVersion"
    }
    return null
}

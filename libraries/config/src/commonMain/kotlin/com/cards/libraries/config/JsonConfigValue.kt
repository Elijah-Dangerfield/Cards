package com.dangerfield.cards.libraries.config

import com.dangerfield.cards.libraries.core.Catching
import com.dangerfield.cards.libraries.core.logOnFailure
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A [ConfiguredValue] whose payload is **structured** rather than a flat scalar.
 * The scalar bases ([FlagConfigValue], [IntConfigValue], …) resolve one dotted
 * path to a `Boolean` / `Int` / `String`; variable-length data — a level ladder,
 * a reward table, a loot table — can't be flattened into fixed scalar paths.
 *
 * [JsonConfigValue] resolves **one** path to whatever JSON subtree lives there
 * (an object or array, already parsed into nested `Map` / `List` by the config
 * pipeline) and deserializes it into a `@Serializable` Kotlin model [T] via the
 * supplied [serializer]. A decode failure — malformed server value, schema drift —
 * falls back to the bundled [default], so a bad remote value can never brick the
 * client.
 *
 * Use it alongside the grouped-interface convention: a `ProgressionConfig`
 * interface whose impl reads scalar knobs through the scalar bases and the
 * structured ladder through this one.
 *
 * ```kotlin
 * @Serializable
 * data class LevelLadder(val xpPerLevel: List<Int>)
 *
 * @Inject
 * @SingleIn(AppScope::class)
 * @ContributesBinding(AppScope::class, ConfiguredValue::class, multibinding = true)
 * class LevelLadderConfig(appConfigMap: AppConfigMap) : JsonConfigValue<LevelLadder>(
 *     appConfigMap = appConfigMap,
 *     serializer = LevelLadder.serializer(),
 * ) {
 *     override val name = "Level ladder"
 *     override val path = "progression.levelLadder"
 *     override val default = LevelLadder(xpPerLevel = DEFAULT_LADDER)
 * }
 * ```
 *
 * A JSON blob doesn't round-trip through the per-key QA dashboard toggles, so
 * these values hide from QA by default ([showInQADashboard] = false). Structured
 * config is tuned server-side, not per-session.
 */
abstract class JsonConfigValue<T : Any>(
    protected val appConfigMap: AppConfigMap,
    private val serializer: KSerializer<T>,
    private val json: Json = ConfigJson,
) : ConfiguredValue<T>() {

    override val showInQADashboard: Boolean = false

    override fun resolveValue(): T {
        val raw = appConfigMap.map.getValueForPath<Any>(fullPath = path) ?: return default
        return Catching { json.decodeFromJsonElement(serializer, raw.toJsonElement()) }
            .logOnFailure { "Failed to decode JSON config at path=$path; using bundled default" }
            .getOrDefault(default)
    }
}

internal val ConfigJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * Converts a value pulled from the parsed config tree (nested `Map` / `List` /
 * scalar) back into a kotlinx [JsonElement] so it can be deserialized into a
 * typed model. The config pipeline parses JSON into plain Kotlin collections;
 * this is the inverse for the structured-value read path.
 */
internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

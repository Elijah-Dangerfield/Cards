package com.dangerfield.cards.libraries.config.impl.serialization

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun JsonObject.toMap(): Map<String, Any?> = buildMap {
    for ((key, value) in this@toMap) {
        put(key, value.toAny())
    }
}

internal fun JsonElement.toAny(): Any? = when (this) {
    JsonNull -> null
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null ->
            longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
    is JsonArray -> map { it.toAny() }
    is JsonObject -> toMap()
}

@Suppress("UNCHECKED_CAST")
internal fun Map<String, *>.toJsonElement(): JsonElement = buildJsonObject {
    for ((key, value) in this@toJsonElement) {
        putJsonElement(key, value)
    }
}

internal fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is String -> JsonPrimitive(this)
    is Map<*, *> -> (this as Map<String, *>).toJsonElement()
    is Iterable<*> -> buildJsonArray {
        this@toJsonElement.forEach { add(it.toJsonElement()) }
    }
    is Array<*> -> buildJsonArray {
        this@toJsonElement.forEach { add(it.toJsonElement()) }
    }
    else -> JsonPrimitive(toString())
}

private fun JsonObjectBuilder.putJsonElement(key: String, value: Any?) {
    when (value) {
        null -> put(key, JsonNull)
        is Boolean -> put(key, value)
        is Number -> put(key, value)
        is String -> put(key, value)
        is Map<*, *> -> putJsonObject(key) {
            (value as Map<String, *>).forEach { (nestedKey, nestedValue) ->
                putJsonElement(nestedKey, nestedValue)
            }
        }
        is Iterable<*> -> putJsonArray(key) {
            value.forEach { add(it.toJsonElement()) }
        }
        is Array<*> -> putJsonArray(key) {
            value.forEach { add(it.toJsonElement()) }
        }
        is JsonElement -> put(key, value)
        else -> put(key, value.toString())
    }
}

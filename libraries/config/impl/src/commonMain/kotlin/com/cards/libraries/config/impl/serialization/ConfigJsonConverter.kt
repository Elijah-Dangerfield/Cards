package com.dangerfield.cards.libraries.config.impl.serialization

import com.dangerfield.cards.libraries.config.ConfigOverride
import com.dangerfield.cards.libraries.core.Catching
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
class ConfigJsonConverter @Inject constructor(
    private val json: Json
) {

    fun decodeToMap(raw: String): Catching<Map<String, Any?>> = Catching {
        val element = json.parseToJsonElement(raw)
        require(element is JsonObject) { "Config json must be a JsonObject" }
        element.toMap()
    }

    fun encodeMap(map: Map<String, *>): Catching<String> = Catching {
        json.encodeToString(JsonElement.serializer(), map.toJsonElement())
    }

    fun decodeOverrides(raw: String): Catching<Set<ConfigOverride<Any>>> = Catching {
        val stored = json.decodeFromString(ListSerializer(StoredConfigOverride.serializer()), raw)
        stored.mapNotNull { entry ->
            entry.value.toAny()?.let { ConfigOverride(entry.path, it) }
        }.toSet()
    }

    fun encodeOverrides(overrides: Collection<ConfigOverride<Any>>): Catching<String> = Catching {
        val stored = overrides.map { override ->
            StoredConfigOverride(
                path = override.path,
                value = override.value.toJsonElement()
            )
        }
        json.encodeToString(ListSerializer(StoredConfigOverride.serializer()), stored)
    }

    @Serializable
    private data class StoredConfigOverride(
        val path: String,
        val value: JsonElement
    )
}

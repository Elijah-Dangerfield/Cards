package com.dangerfield.cards.libraries.storage

import androidx.datastore.core.CorruptionException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

class VersionedCacheJsonSerializer<T : Any>(
    private val json: Json,
    private val serializer: KSerializer<T>,
    private val defaultValue: () -> T,
    private val migrations: List<Migration> = emptyList(),
    private val encryption: CacheEncryption = NoEncryption,
) : CacheJsonSerializer<T> {

    private val currentVersion: Int = migrations.size + 1

    override suspend fun read(bytes: ByteArray?): T {
        if (bytes == null || bytes.isEmpty()) return defaultValue()

        val decrypted = encryption.decrypt(bytes)
        val text = decrypted.decodeToString()

        val root = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw CorruptionException("Failed to parse JSON", e)
        }

        val rootObj = root as? JsonObject
            ?: throw CorruptionException("Root JSON is not an object")

        val initialVersion = rootObj["v"]?.jsonPrimitive?.intOrNull ?: 1
        if (initialVersion > currentVersion) {
            throw CorruptionException("Cannot downgrade from $initialVersion to $currentVersion")
        }

        val initialData = rootObj["d"]
            ?: throw CorruptionException("Missing 'd' field")

        val migratedData = migrateIfNeeded(initialData, initialVersion)

        return try {
            json.decodeFromJsonElement(serializer, migratedData)
        } catch (e: Exception) {
            throw CorruptionException("Failed to decode versioned data", e)
        }
    }

    override suspend fun write(value: T): ByteArray {
        val data = json.encodeToJsonElement(serializer, value)
        val root = buildJsonObject {
            put("v", JsonPrimitive(currentVersion))
            put("d", data)
        }
        val text = json.encodeToString(JsonObject.serializer(), root)
        val bytes = text.encodeToByteArray()
        return encryption.encrypt(bytes)
    }

    private suspend fun migrateIfNeeded(
        initialData: JsonElement,
        initialVersion: Int,
    ): JsonElement {
        var v = initialVersion
        var element = initialData

        while (v < currentVersion) {
            val migration = migrations[v - 1] // v=1 -> index 0
            element = migration.migrate(element)
            v++
        }

        return element
    }

    fun interface Migration {
        suspend fun migrate(data: JsonElement): JsonElement
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        inline fun <reified Old : Any, reified New : Any> typedMigration(
            json: Json,
            noinline migrate: (Old) -> New,
        ): Migration {
            val oldSer = json.serializersModule.serializer<Old>()
            val newSer = json.serializersModule.serializer<New>()

            return Migration { data ->
                val old = json.decodeFromJsonElement(oldSer, data)
                val migrated = migrate(old)
                json.encodeToJsonElement(newSer, migrated)
            }
        }
    }
}


@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T : Any> versionedJsonSerializer(
    json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    },
    noinline defaultValue: () -> T,
    migrations: List<VersionedCacheJsonSerializer.Migration> = emptyList(),
    encryption: CacheEncryption? = null,
): CacheJsonSerializer<T> {
    val kSerializer = json.serializersModule.serializer<T>()
    return VersionedCacheJsonSerializer(
        json = json,
        serializer = kSerializer,
        defaultValue = defaultValue,
        migrations = migrations,
        encryption = encryption ?: NoEncryption,
    )
}

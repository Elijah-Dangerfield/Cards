package com.dangerfield.cards.libraries.storage.impl.db

import androidx.room.ProvidedTypeConverter
import androidx.room.TypeConverter
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Type converters for Room database.
 *
 * Handles conversion of:
 * - Instant <-> Long (epoch milliseconds)
 * - List<String> <-> String (JSON)
 * - Map<String, String> <-> String (JSON)
 */
@ProvidedTypeConverter
class CoreTypeConverters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val stringMapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val intMapSerializer = MapSerializer(String.serializer(), Int.serializer())
    private val longMapSerializer = MapSerializer(String.serializer(), Long.serializer())

    // ========== Instant <-> Long ==========

    @TypeConverter
    fun instantToEpoch(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun epochToInstant(value: Long?): Instant? = value?.let(Instant::fromEpochMilliseconds)

    // ========== List<String> <-> String ==========

    @TypeConverter
    fun stringListToString(value: List<String>?): String =
        value?.let { json.encodeToString(stringListSerializer, it) } ?: "[]"

    @TypeConverter
    fun stringToStringList(value: String?): List<String> =
        if (value.isNullOrBlank() || value == "[]") {
            emptyList()
        } else {
            json.decodeFromString(stringListSerializer, value)
        }

    // ========== Map<String, String> <-> String ==========

    @TypeConverter
    fun stringMapToString(value: Map<String, String>?): String =
        value?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(stringMapSerializer, it) } ?: "{}"

    @TypeConverter
    fun stringToStringMap(value: String?): Map<String, String> =
        if (value.isNullOrBlank() || value == "{}") {
            emptyMap()
        } else {
            json.decodeFromString(stringMapSerializer, value)
        }

    // ========== Map<String, Int> <-> String ==========

    @TypeConverter
    fun intMapToString(value: Map<String, Int>?): String =
        value?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(intMapSerializer, it) } ?: "{}"

    @TypeConverter
    fun stringToIntMap(value: String?): Map<String, Int> =
        if (value.isNullOrBlank() || value == "{}") {
            emptyMap()
        } else {
            json.decodeFromString(intMapSerializer, value)
        }

    // ========== Map<String, Long> <-> String ==========

    @TypeConverter
    fun longMapToString(value: Map<String, Long>?): String =
        value?.takeIf { it.isNotEmpty() }?.let { json.encodeToString(longMapSerializer, it) } ?: "{}"

    @TypeConverter
    fun stringToLongMap(value: String?): Map<String, Long> =
        if (value.isNullOrBlank() || value == "{}") {
            emptyMap()
        } else {
            json.decodeFromString(longMapSerializer, value)
        }
}

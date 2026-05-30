package com.dangerfield.cards.libraries.config.impl.serialization

import com.dangerfield.cards.libraries.config.ConfigOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the JSON ↔ Kotlin value contract that flows under
 * [ConfigOverrideRepositoryImpl], [OfflineFirstAppConfigRepository] and the
 * QA-menu override surface:
 *
 *  - Primitive-type ordering inside [JsonElement.toAny]: string-flag first,
 *    then bool, then long, then double, then content-as-string. The wrong
 *    order silently misclassifies a `"true"` string as a Boolean, or a
 *    `"42"` string as a Long — both real corruption modes for QA overrides
 *    persisted across launches.
 *  - Round-trip preservation for the four supported override types
 *    (Boolean / Long / String / nested map). Float/double round-trips as
 *    Double, not Float — pinned so a future "let's keep Float as Float"
 *    refactor is an intentional change, not an accidental one.
 *  - Silent drop of `null`-valued overrides in `decodeOverrides`:
 *    `mapNotNull { entry.value.toAny()?.let { ... } }` makes a stored
 *    `{"path":"x","value":null}` round-trip as nothing. Documenting the
 *    behavior here makes future un-dropping (if `null` overrides ever need
 *    to mean "explicit null") a loud, intentional change.
 *  - Malformed JSON returns a failure inside [Catching], not a thrown
 *    exception that propagates to the caller — that's what guarantees the
 *    `decodeOverrides` fall-back-to-empty path in the repo.
 */
class ConfigJsonConverterTest {

    private val converter = ConfigJsonConverter(Json { ignoreUnknownKeys = true })

    // ---------- decodeOverrides ----------

    @Test
    fun decodeOverrides_singlePrimitiveOverride_roundTripsAsExpectedType() {
        val raw = """[{"path":"feature.flag","value":true}]"""

        val decoded = converter.decodeOverrides(raw).getOrNull() ?: fail("decode failed")

        assertEquals(1, decoded.size)
        val override = decoded.single()
        assertEquals("feature.flag", override.path)
        assertEquals(true, override.value)
        assertTrue(override.value is Boolean)
    }

    @Test
    fun decodeOverrides_integerValue_arrivesAsLong_notInt() {
        val raw = """[{"path":"limit","value":42}]"""

        val decoded = converter.decodeOverrides(raw).getOrNull() ?: fail("decode failed")
        val value = decoded.single().value

        assertEquals(42L, value)
        assertTrue(value is Long, "JSON integers decode as Long, not Int (longOrNull precedence)")
    }

    @Test
    fun decodeOverrides_decimalValue_arrivesAsDouble() {
        val raw = """[{"path":"ratio","value":1.5}]"""

        val decoded = converter.decodeOverrides(raw).getOrNull() ?: fail("decode failed")
        val value = decoded.single().value

        assertEquals(1.5, value)
        assertTrue(value is Double, "JSON decimals decode as Double")
    }

    @Test
    fun decodeOverrides_stringNumeric_staysString_notNumber() {
        val raw = """[{"path":"version","value":"42"}]"""

        val decoded = converter.decodeOverrides(raw).getOrNull() ?: fail("decode failed")
        val value = decoded.single().value

        assertEquals("42", value)
        assertTrue(value is String, "isString flag must win over longOrNull — '42' stays a String")
    }

    @Test
    fun decodeOverrides_stringBoolean_staysString_notBoolean() {
        val raw = """[{"path":"flag","value":"true"}]"""

        val decoded = converter.decodeOverrides(raw).getOrNull() ?: fail("decode failed")
        val value = decoded.single().value

        assertEquals("true", value)
        assertTrue(value is String, "isString flag must win over booleanOrNull")
    }

    @Test
    fun decodeOverrides_nullValue_isSilentlyDropped() {
        val raw = """[{"path":"missing","value":null},{"path":"present","value":"v"}]"""

        val decoded = converter.decodeOverrides(raw).getOrNull() ?: fail("decode failed")

        assertEquals(1, decoded.size, "null values are filtered (mapNotNull on toAny())")
        assertEquals("present", decoded.single().path)
    }

    @Test
    fun decodeOverrides_emptyArray_returnsEmptySet() {
        val raw = "[]"

        val decoded = converter.decodeOverrides(raw).getOrNull() ?: fail("decode failed")

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun decodeOverrides_malformedJson_returnsFailure_doesNotThrow() {
        val result = converter.decodeOverrides("not valid json")

        assertTrue(result.isFailure, "malformed JSON must return Catching.failure, not throw")
    }

    @Test
    fun decodeOverrides_objectShape_notArray_returnsFailure() {
        val raw = """{"path":"x","value":1}"""

        val result = converter.decodeOverrides(raw)

        assertTrue(result.isFailure, "decoder expects a list — a top-level object is malformed shape")
    }

    // ---------- encodeOverrides ----------

    @Test
    fun encodeOverrides_thenDecode_roundTripPreservesValues() {
        val originals = listOf(
            ConfigOverride("feature.flag", true as Any),
            ConfigOverride("max.players", 8L as Any),
            ConfigOverride("ui.title", "Cards" as Any),
        )

        val json = converter.encodeOverrides(originals).getOrNull() ?: fail("encode failed")
        val decoded = converter.decodeOverrides(json).getOrNull() ?: fail("decode failed")

        val byPath = decoded.associate { it.path to it.value }
        assertEquals(true, byPath["feature.flag"])
        assertEquals(8L, byPath["max.players"])
        assertEquals("Cards", byPath["ui.title"])
    }

    @Test
    fun encodeOverrides_emptyList_producesValidJsonArray() {
        val json = converter.encodeOverrides(emptyList()).getOrNull() ?: fail("encode failed")
        assertEquals("[]", json)
    }

    @Test
    fun encodeOverrides_unsupportedValueType_fallsBackToToString() {
        val override = ConfigOverride("weird", PointLike(x = 1, y = 2) as Any)

        val json = converter.encodeOverrides(listOf(override)).getOrNull() ?: fail("encode failed")
        val decoded = converter.decodeOverrides(json).getOrNull() ?: fail("decode failed")

        assertEquals("PointLike(1,2)", decoded.single().value, "unknown types are encoded via toString")
    }

    // ---------- decodeToMap / encodeMap (used by FallbackConfigMap + RemoteConfigDataSource) ----------

    @Test
    fun decodeToMap_nestedJsonObject_decodesAsNestedMap() {
        val raw = """{"feature":{"flag":true,"limit":10}}"""

        val map = converter.decodeToMap(raw).getOrNull() ?: fail("decode failed")

        val nested = map["feature"] as? Map<*, *> ?: fail("expected nested Map for 'feature'")
        assertEquals(true, nested["flag"])
        assertEquals(10L, nested["limit"])
    }

    @Test
    fun decodeToMap_jsonArrayField_decodesAsList() {
        val raw = """{"tiers":["bronze","silver","gold"]}"""

        val map = converter.decodeToMap(raw).getOrNull() ?: fail("decode failed")

        assertEquals(listOf("bronze", "silver", "gold"), map["tiers"])
    }

    @Test
    fun decodeToMap_jsonArray_notObject_returnsFailure() {
        val result = converter.decodeToMap("[1,2,3]")

        assertTrue(result.isFailure, "top-level array isn't a Map — must fail")
    }

    @Test
    fun encodeMap_thenDecode_roundTripsNestedShape() {
        val original: Map<String, Any?> = mapOf(
            "name" to "shop",
            "enabled" to true,
            "limits" to mapOf(
                "perDay" to 50L,
                "perHour" to 10L,
            ),
            "labels" to listOf("a", "b"),
        )

        val json = converter.encodeMap(original).getOrNull() ?: fail("encode failed")
        val decoded = converter.decodeToMap(json).getOrNull() ?: fail("decode failed")

        assertEquals("shop", decoded["name"])
        assertEquals(true, decoded["enabled"])
        val limits = decoded["limits"] as? Map<*, *> ?: fail("expected nested map")
        assertEquals(50L, limits["perDay"])
        assertEquals(10L, limits["perHour"])
        assertEquals(listOf("a", "b"), decoded["labels"])
    }

    @Test
    fun encodeMap_passThroughJsonElement_preservesType() {
        val original: Map<String, Any?> = mapOf(
            "raw" to JsonObject(mapOf("nested" to JsonPrimitive("v"))),
            "absent" to JsonNull,
        )

        val json = converter.encodeMap(original).getOrNull() ?: fail("encode failed")
        val decoded = converter.decodeToMap(json).getOrNull() ?: fail("decode failed")

        val rawDecoded = decoded["raw"] as? Map<*, *> ?: fail("expected nested map from JsonObject")
        assertEquals("v", rawDecoded["nested"])
        assertEquals(null, decoded["absent"], "JsonNull encodes as null and round-trips as null")
    }

    private data class PointLike(val x: Int, val y: Int) {
        override fun toString(): String = "PointLike($x,$y)"
    }
}

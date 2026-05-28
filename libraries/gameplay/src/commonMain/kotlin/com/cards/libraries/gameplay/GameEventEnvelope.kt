package com.dangerfield.cards.libraries.gameplay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * On-disk envelope for a persisted [GameEvent].
 *
 * The persisted row in `game_events.event_jsonb` always carries this
 * three-field shape:
 *
 * ```json
 * { "v": 1, "type": "HandStarted", "payload": { … event fields … } }
 * ```
 *
 * Why: schema evolution on events is forever. A v2 reader needs to
 * fork on [version] without rewriting every historical row — the
 * outer envelope gives that fork point, isolated from the payload's
 * internal shape. [type] is the stable discriminator the v1 reader
 * dispatches on (matches each [GameEvent] subclass's `@SerialName`);
 * [payload] is the kotlinx-serialized event body, the same shape the
 * WS broadcast carries.
 *
 * Wire / WS frames continue to use the bare kotlinx polymorphic JSON
 * (`{ "type": "...", ... }`) — the envelope is a persistence concern,
 * not a transport one.
 */
@Serializable
data class GameEventEnvelope(
    @SerialName("v") val version: Int,
    @SerialName("type") val type: String,
    @SerialName("payload") val payload: JsonObject,
) {
    companion object {
        const val CURRENT_VERSION: Int = 1
    }
}

/**
 * `Json` config used for envelope encode/decode. Uses `"type"` as the
 * class discriminator to match each [GameEvent] subclass's
 * `@SerialName`, and `encodeDefaults = true` so a defaulted field on
 * the event still lands in the persisted JSONB (replay must be
 * deterministic).
 */
val GameEventEnvelopeJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
}

/**
 * Wrap [this] event in a v[GameEventEnvelope.CURRENT_VERSION] envelope.
 * The kotlinx `"type"` discriminator is lifted from the serialized
 * event body into the envelope's [GameEventEnvelope.type] field — the
 * payload stays clean.
 */
fun GameEvent.toEnvelope(json: Json = GameEventEnvelopeJson): GameEventEnvelope {
    val serialized = json.encodeToJsonElement(GameEvent.serializer(), this).jsonObject
    val type = (serialized[CLASS_DISCRIMINATOR] as? JsonPrimitive)?.content
        ?: error("GameEvent serialization missing '$CLASS_DISCRIMINATOR' discriminator: $serialized")
    val payload = JsonObject(serialized - CLASS_DISCRIMINATOR)
    return GameEventEnvelope(
        version = GameEventEnvelope.CURRENT_VERSION,
        type = type,
        payload = payload,
    )
}

/**
 * Inverse of [toEnvelope]. Re-inserts the discriminator the polymorphic
 * `GameEvent` serializer needs and decodes.
 *
 * Throws [IllegalStateException] if [GameEventEnvelope.version] is
 * something this binary doesn't know about — the caller decides whether
 * to skip the row or fail loudly.
 */
fun GameEventEnvelope.toGameEvent(json: Json = GameEventEnvelopeJson): GameEvent {
    check(version == GameEventEnvelope.CURRENT_VERSION) {
        "unsupported GameEventEnvelope version: $version"
    }
    val withType = JsonObject(payload + (CLASS_DISCRIMINATOR to JsonPrimitive(type)))
    return json.decodeFromJsonElement(GameEvent.serializer(), withType)
}

private const val CLASS_DISCRIMINATOR = "type"

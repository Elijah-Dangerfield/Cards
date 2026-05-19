package com.dangerfield.cards.libraries.rooms.impl

import kotlinx.serialization.json.Json

/**
 * Json config for socket frames. Discriminator key is `type` to match
 * the server's `RoomSocketEventDto` serializer. `ignoreUnknownKeys`
 * means a stale client tolerates new fields the server adds.
 *
 * Unknown discriminator values throw `SerializationException` at
 * decode time — the call site in [ReconnectingRoomSocket] catches and
 * drops the frame. That's intentional: this Json instance stays the
 * standard kotlinx-serialization shape, and forward-compat lives in
 * the one place that runs the decode.
 */
internal val RoomSocketJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
}

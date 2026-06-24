package com.dangerfield.cards.libraries.rooms.impl

import com.dangerfield.cards.libraries.core.BuildInfo
import kotlinx.serialization.json.Json

/**
 * Json config for socket frames. Discriminator key is `type` to match
 * the server's `RoomSocketEventDto` serializer. `ignoreUnknownKeys`
 * means a stale client tolerates new fields the server adds.
 *
 * `coerceInputValues` is build-gated like [NetworkJson]: in release it
 * coerces an unknown enum *value* (a future 4th [RoomStatusDto] /
 * [RoomVisibilityDto] this client doesn't know) to the property's default
 * ([RoomStatusDto.Unknown] / [RoomVisibilityDto.Private]) instead of throwing,
 * so a server enum addition doesn't drop the whole room snapshot and freeze
 * the live UI. Debug stays strict so contract drift fails loud during
 * development. An unknown *discriminator* value still throws either way — the
 * call site in [ReconnectingRoomSocket] catches and drops that frame; there's
 * no safe default for a frame type the client can't dispatch.
 */
internal val RoomSocketJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    coerceInputValues = !BuildInfo.isDebug
}

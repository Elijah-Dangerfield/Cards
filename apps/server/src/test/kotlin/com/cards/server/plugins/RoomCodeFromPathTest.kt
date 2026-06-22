package com.dangerfield.cards.server.plugins

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [roomCodeFromPath], which lifts `room_code` into the CallLogging MDC so
 * every room-route log line is filterable in Loki by room (the load-bearing
 * pivot for multiplayer triage — one room spans many sessions). The two things
 * that would silently break it: (1) the non-room `POST /v1/rooms` create path
 * false-matching and tagging every create log with a bogus code, and (2) the
 * value drifting out of sync with the handlers' uppercased `code` + the span
 * `room.code` attribute. Both are pinned below.
 */
class RoomCodeFromPathTest {

    @Test
    fun extractsCode_fromEachRoomRouteShape() {
        assertEquals("A3DTHY", roomCodeFromPath("/v1/rooms/A3DTHY"))
        assertEquals("A3DTHY", roomCodeFromPath("/v1/rooms/A3DTHY/join"))
        assertEquals("A3DTHY", roomCodeFromPath("/v1/rooms/A3DTHY/me"))
        assertEquals("A3DTHY", roomCodeFromPath("/v1/rooms/A3DTHY/bots"))
        assertEquals("A3DTHY", roomCodeFromPath("/v1/rooms/A3DTHY/bots/some-bot-id"))
        assertEquals("A3DTHY", roomCodeFromPath("/v1/rooms/A3DTHY/socket"))
    }

    @Test
    fun uppercases_toMatchCanonicalCodeAndSpanAttribute() {
        // Handlers do `call.parameters["code"]?.uppercase()` and the span
        // `room.code` carries the uppercased code; the log field must agree so
        // one query string works across Loki + Tempo.
        assertEquals("A3DTHY", roomCodeFromPath("/v1/rooms/a3dthy/socket"))
    }

    @Test
    fun returnsNull_forCreateAndNonRoomPaths() {
        // `POST /v1/rooms` (create) has no trailing segment — must NOT match,
        // else every create log is tagged with a phantom room code.
        assertNull(roomCodeFromPath("/v1/rooms"))
        assertNull(roomCodeFromPath("/v1/me/active-rooms"))
        assertNull(roomCodeFromPath("/v1/me"))
        assertNull(roomCodeFromPath("/_health"))
    }
}

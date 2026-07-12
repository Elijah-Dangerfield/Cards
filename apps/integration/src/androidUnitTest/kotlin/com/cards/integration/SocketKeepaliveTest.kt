package com.cards.integration

import com.cards.integration.helpers.IntegrationTest
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome
import com.dangerfield.cards.libraries.rooms.RoomConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MP-32 regression: a quiet room socket must NOT drop on a keepalive cadence.
 *
 * The client is built [wiretapWrapped] — the shape every debug build runs,
 * where the Wiretap WS inspector wraps the raw engine session. Before the fix,
 * that wrapper plus a plugin-level `pingIntervalMillis` killed the socket at
 * exactly 15s (OkHttp can't write a raw `Frame.Ping`), so an idle MP table
 * flashed the "lost connection" banner three times a minute. The quiet window
 * here extends past that death point plus margin: one connection, zero
 * reconnects, still connected at the end.
 *
 * Real wall-clock seconds by design — the failure was a real-time race between
 * ping schedulers, unreachable under virtual time.
 */
class SocketKeepaliveTest : IntegrationTest() {

    @Test
    fun quietSocket_debugInstrumented_holdsWellPastPingInterval() = integration {
        val host = client(wiretapWrapped = true)
        val created = host.repository.createRoom()
        check(created is CreateRoomOutcome.Success) { "createRoom failed: $created" }

        val handle = host.connect(created.room.code)
        val emissions = CopyOnWriteArrayList<RoomConnection>()
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            handle.connection.collect { emissions += it }
        }
        withTimeout(10_000) {
            handle.connection.filterIsInstance<RoomConnection.Connected>().first()
        }

        // Past the 15s death point with margin; the socket carries no frames
        // in this window except keepalive pings.
        delay(18_000)
        watcher.cancelAndJoin()

        val drops = emissions.filter { it is RoomConnection.Reconnecting || it is RoomConnection.Closed }
        assertTrue(
            drops.isEmpty(),
            "quiet socket should hold a single connection, but saw: $drops (all: $emissions)",
        )
        assertTrue(
            emissions.last() is RoomConnection.Connected,
            "expected to still be connected after the quiet window, was: ${emissions.last()}",
        )
    }
}

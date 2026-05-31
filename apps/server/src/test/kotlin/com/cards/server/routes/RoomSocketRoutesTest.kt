package com.dangerfield.cards.server.routes

import com.dangerfield.cards.server.data.createOrFail
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.game.DefaultGameSessionRegistry
import com.dangerfield.cards.server.game.SeatOccupant
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * End-to-end tests for the per-room WebSocket. Spins up a real Ktor
 * test server with the WebSockets plugin + a real test client that
 * opens a socket, listens for events, and asserts the wire protocol.
 *
 * What we pin:
 *  - Connect → first frame is a Snapshot with the current room.
 *  - markConnected flips true on connect, false on disconnect.
 *  - Second client joining (via HTTP) → existing socket receives a
 *    Snapshot + MemberJoined delta.
 *  - First client disconnecting → second socket sees a
 *    MemberPresenceChanged(isConnected=false) event.
 *  - Reconnect by the SAME userId preserves the seat (no duplicate
 *    member, same seatIndex).
 *  - Non-member (didn't POST /join) gets the socket closed with
 *    CANNOT_ACCEPT.
 *
 * The reconnect-flow tests are the load-bearing ones the user
 * specifically called out — these are the gnarliest pieces of the MP
 * surface and the ones most likely to regress.
 */
@OptIn(ExperimentalTime::class)
class RoomSocketRoutesTest {

    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    @Test
    fun connect_firstFrameIsSnapshot_andMarksMemberConnected() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host")
        withRoomSocketTestApp(rooms) { client ->
            client.openSocket(room.code, asUser = host) { session ->
                val event = session.receiveOne()
                val snap = assertIs<RoomSocketEventDto.Snapshot>(event)
                assertEquals(room.code, snap.room.code)
                assertEquals(1, snap.room.members.size)
                // The snapshot reflects post-connect state — isConnected is true.
                assertTrue(snap.room.members.single().isConnected)
            }
            // The server's onClose handler runs in a coroutine after the
            // client closes. Poll until it lands so the assertion isn't
            // racing the flip.
            awaitDisconnected(rooms, room.code, host)
        }
    }

    @Test
    fun nonMember_socketIsRejected() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host")
        withRoomSocketTestApp(rooms) { client ->
            client.openSocket(room.code, asUser = alice) { session ->
                // We expect the server to close the socket immediately.
                // Receiving from a closed channel throws.
                assertTrue(session.expectClosed())
            }
        }
    }

    @Test
    fun unknownRoom_socketIsRejected() = runTest {
        val rooms = newRoomService()
        withRoomSocketTestApp(rooms) { client ->
            client.openSocket("ZZZZZZ", asUser = host) { session ->
                assertTrue(session.expectClosed())
            }
        }
    }

    @Test
    fun secondJoin_broadcastsToFirstSocket() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        withRoomSocketTestApp(rooms) { client ->
            client.openSocket(room.code, asUser = host) { hostSession ->
                // Drain the initial snapshot.
                hostSession.receiveOne()

                // Alice joins via service (simulates HTTP /join).
                rooms.join(room.code, alice, "Alice")

                // The host socket should receive a Snapshot + a
                // MemberJoined delta (in that order — Snapshot first
                // is the always-correct fallback).
                val first = hostSession.receiveOne()
                val snap = assertIs<RoomSocketEventDto.Snapshot>(first)
                assertEquals(2, snap.room.members.size)

                val second = hostSession.receiveOne()
                val joined = assertIs<RoomSocketEventDto.MemberJoined>(second)
                assertEquals(alice.value.toString(), joined.member.userId)
                assertEquals(1, joined.member.seatIndex)
            }
        }
    }

    @Test
    fun disconnect_broadcastsPresenceFlip_toOtherSockets() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        withRoomSocketTestApp(rooms) { client ->
            client.openSocket(room.code, asUser = host) { hostSession ->
                hostSession.receiveOne() // host snapshot

                client.openSocket(room.code, asUser = alice) { aliceSession ->
                    aliceSession.receiveOne() // alice snapshot
                    // Host sees alice's presence flip to true.
                    val snapshotForAliceConnect = hostSession.receiveOne()
                    assertIs<RoomSocketEventDto.Snapshot>(snapshotForAliceConnect)
                    val aliceConnected = hostSession.receiveOne()
                    val delta = assertIs<RoomSocketEventDto.MemberPresenceChanged>(aliceConnected)
                    assertEquals(alice.value.toString(), delta.userId)
                    assertEquals(true, delta.isConnected)
                }
                // After Alice's socket closes, host sees her presence flip back.
                val snapshotForAliceDisconnect = hostSession.receiveOne()
                assertIs<RoomSocketEventDto.Snapshot>(snapshotForAliceDisconnect)
                val aliceDisconnected = hostSession.receiveOne()
                val delta = assertIs<RoomSocketEventDto.MemberPresenceChanged>(aliceDisconnected)
                assertEquals(alice.value.toString(), delta.userId)
                assertEquals(false, delta.isConnected)
            }
        }
    }

    @Test
    fun disconnect_schedulesReaper_thatFreesTheSeat() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        // Tight grace so the test doesn't sit on a real timer.
        withRoomSocketTestApp(rooms, reaperGrace = 50.milliseconds) { client ->
            client.openSocket(room.code, asUser = alice) { aliceSession ->
                aliceSession.receiveOne() // drain the snapshot
            }
            // Reaper fires after the grace window — alice's seat
            // should disappear without anyone calling sweepDisconnected.
            awaitReaped(rooms, room.code, alice)
            val survivors = rooms.find(room.code)!!.members
            assertEquals(1, survivors.size)
            assertEquals(host, survivors.single().userId)
        }
    }

    @Test
    fun reconnectBeforeGrace_cancelsReaperEffect() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        // First disconnect schedules a reaper with stamp1; the reconnect
        // clears that stamp before the grace elapses, so the original
        // reaper must short-circuit and alice stays seated.
        withRoomSocketTestApp(rooms, reaperGrace = 250.milliseconds) { client ->
            client.openSocket(room.code, asUser = alice) { first ->
                first.receiveOne()
            }
            client.openSocket(room.code, asUser = alice) { second ->
                second.receiveOne()
                // Hold this socket open past the first reaper's grace
                // window so we can observe it no-op.
                delay(500)
                assertEquals(
                    2,
                    rooms.find(room.code)!!.members.size,
                    "reconnect before grace must invalidate the prior reaper",
                )
            }
        }
    }

    @Test
    fun connect_afterRestart_hydratesGameStateFromSnapshot() = runTest {
        // Simulates the §B0 ↔ §B1 hand-off: a hand was started on a
        // previous server process (snapshot lives in the durable store),
        // the process restarted (a fresh registry with empty in-memory
        // state takes its place), and a player reconnects. The WS
        // upgrade must hydrate the session from the store so the game
        // publisher emits a GameStateSnapshot frame without waiting for
        // an intent to trigger lazy hydration.
        val store = InMemoryTestSnapshotStore()
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        // Seed the durable store via a throwaway registry — equivalent
        // to "the previous server process started a hand here."
        val seedRegistry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)
        val occupants = listOf(
            SeatOccupant(seatIndex = 0, userId = host.value.toString(), displayName = "Host", isBot = false),
            SeatOccupant(seatIndex = 1, userId = alice.value.toString(), displayName = "Alice", isBot = false),
        )
        seedRegistry.startHand(
            code = room.code,
            occupants = occupants,
            settings = RoomSettings(
                smallBlind = 5,
                bigBlind = 10,
                startingStack = 1_000,
                maxSeats = 6,
                turnTimerSeconds = 30,
            ),
        )
        val seededHandNumber = seedRegistry.peek(room.code)!!.state.value!!.handNumber

        // Fresh registry against the same store — nothing in-memory.
        val freshRegistry = DefaultGameSessionRegistry(snapshotStore = store, clock = Clock.System)

        withRoomSocketTestApp(rooms, gameSessions = freshRegistry) { client ->
            client.openSocket(room.code, asUser = host) { session ->
                // The lobby Snapshot is always first; the GameStateSnapshot
                // arrives once findOrHydrate populates the in-memory registry
                // and the publisher subscribes. receiveUntilGameState skips
                // (and keeps) the intervening frames.
                val gameSnapshot = session.receiveUntilGameState()
                assertEquals(seededHandNumber, gameSnapshot.state.handNumber)
                assertEquals(2, gameSnapshot.state.seats.size)
            }
        }
    }

    @Test
    fun reconnectSameUser_preservesSeat_andDoesNotDuplicateMember() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val originalSeatIndex = rooms.find(room.code)!!.memberFor(alice)!!.seatIndex

        withRoomSocketTestApp(rooms) { client ->
            // Connect, then close.
            client.openSocket(room.code, asUser = alice) { aliceSession ->
                aliceSession.receiveOne()
            }
            // Reconnect: should still be the same member, same seat.
            client.openSocket(room.code, asUser = alice) { aliceSession ->
                val snap = assertIs<RoomSocketEventDto.Snapshot>(aliceSession.receiveOne())
                assertEquals(2, snap.room.members.size, "no duplicate member created")
                val alicePostReconnect = snap.room.members.single { it.userId == alice.value.toString() }
                assertEquals(originalSeatIndex, alicePostReconnect.seatIndex)
                assertTrue(alicePostReconnect.isConnected)
            }
        }
    }

}

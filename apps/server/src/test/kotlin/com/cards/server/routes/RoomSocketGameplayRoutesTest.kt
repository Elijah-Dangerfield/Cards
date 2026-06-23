package com.dangerfield.cards.server.routes

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.HandParticipation
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.gameplay.SeatStatus
import com.dangerfield.cards.server.data.createOrFail
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the **gameplay** leg of the per-room WebSocket —
 * Round 4 of [docs/testing-plan.md]. [RoomSocketRoutesTest] covers the
 * lobby / presence / reconnect flow; [GameSessionTest] +
 * [com.cards.server.game.GameSessionRegistryIntegrationTest] cover the
 * session/registry in isolation. Nothing exercised the plumbing
 * *between* the WS route and the registry — the wire-level decode →
 * dispatch → per-recipient broadcast cycle — which is where wire
 * regressions hide.
 *
 * Each test brings up a real Ktor server with a real
 * [DefaultGameSessionRegistry] and drives it through real client
 * sockets sending real [RoomClientFrame] bytes.
 *
 * What we pin:
 *  - `StartHand` from the host applies to the engine + broadcasts a
 *    `GameStateSnapshot`; from a non-host it's rejected with an
 *    `IntentAck(accepted=false)` and no session is created.
 *  - A second `StartHand` while a hand is in progress is rejected.
 *  - `SubmitIntent` (valid) applies + broadcasts; (out-of-turn) is
 *    rejected and leaves engine state untouched; (duplicate nonce) is
 *    idempotently accepted without re-applying.
 *  - `RequestNextHand` from any seated player (not just the host)
 *    advances to the next hand.
 *  - `GameStateSnapshot` is scrubbed per recipient — the viewer sees
 *    their own hole cards, never an opponent's.
 *  - `GameEventOccurred` frames carry monotonically increasing
 *    sequence numbers.
 *  - A socket dropping mid-hand doesn't stall the engine — the other
 *    seat's action still processes.
 *
 * NOT covered here: engine rule correctness (`:libraries:gameplay`),
 * reconnect/hydration (covered in [RoomSocketRoutesTest]), and the
 * client-side frame projection ([RemotePokerSessionTest]).
 *
 * Server bring-up, auth, the client socket wrapper and the order-
 * independent frame readers all live in [RoomSocketTestClient] /
 * [withRoomSocketTestApp] (see RoomSocketTestSupport.kt).
 */
class RoomSocketGameplayRoutesTest {

    private val host = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val alice = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))

    // ===================================================================
    // StartHand
    // ===================================================================

    @Test
    fun startHand_fromHost_acksAccepted_andBroadcastsGameStateSnapshot() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withRoomSocketTestApp(rooms) { client ->
            val socket = client.connect(room.code, host)
            try {
                socket.drainSnapshot()
                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "start-1"))

                val ack = socket.receiveUntilAck("start-1")
                assertTrue(ack.accepted, "host start should be accepted; error=${ack.error}")

                val snapshot = socket.receiveUntilGameState()
                assertEquals(1, snapshot.state.handNumber)
                assertEquals(2, snapshot.state.seats.size)
            } finally {
                socket.closeQuietly()
            }
        }
    }

    @Test
    fun startHand_fromNonHost_isRejected_andNoSessionCreated() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withRoomSocketTestApp(rooms, registry) { client ->
            val socket = client.connect(room.code, alice)
            try {
                socket.drainSnapshot()
                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "nh-1"))

                val ack = socket.receiveUntilAck("nh-1")
                assertTrue(!ack.accepted, "non-host start must be rejected")
                assertTrue(
                    ack.error?.contains("host") == true,
                    "rejection should explain host-gating; was '${ack.error}'",
                )
                // Defense-in-depth: the engine never started.
                assertNull(registry.peek(room.code), "no session should exist after a rejected start")
            } finally {
                socket.closeQuietly()
            }
        }
    }

    @Test
    fun startHand_whenHandInProgress_isRejected() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withRoomSocketTestApp(rooms) { client ->
            val socket = client.connect(room.code, host)
            try {
                socket.drainSnapshot()
                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s1"))
                assertTrue(socket.receiveUntilAck("s1").accepted)
                socket.receiveUntilGameState() // hand is now live

                socket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s2"))
                val ack = socket.receiveUntilAck("s2")
                assertTrue(!ack.accepted, "second start mid-hand must be rejected")
                assertTrue(
                    ack.error?.contains("progress") == true,
                    "rejection should explain a hand is already running; was '${ack.error}'",
                )
            } finally {
                socket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // SubmitIntent
    // ===================================================================

    @Test
    fun submitIntent_validFold_isAccepted_andBroadcastsCompletedHand() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withRoomSocketTestApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()

                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                // Act as whichever seat the engine put on the button —
                // heads-up the first actor varies with the shuffle.
                val actingSeat = opening.state.actingSeatIndex!!
                val actorUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val actorSocket = if (actorUser == host.value.toString()) hostSocket else aliceSocket

                actorSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(
                        intent = PlayerIntent.Fold(seatIndex = actingSeat),
                        clientNonce = "fold-1",
                    ),
                )
                assertTrue(actorSocket.receiveUntilAck("fold-1").accepted)

                // Heads-up fold ends the hand; a Complete snapshot fans out.
                val completed = actorSocket.receiveUntilGameState { it.street == BettingRound.Complete }
                assertEquals(BettingRound.Complete, completed.state.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    @Test
    fun submitIntent_outOfTurn_isRejected_andStateUnchanged() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withRoomSocketTestApp(rooms, registry) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                val actingSeat = opening.state.actingSeatIndex!!
                val idleSeat = opening.state.seats.first { it.index != actingSeat }
                val idleUser = idleSeat.playerId!!
                val idleSocket = if (idleUser == host.value.toString()) hostSocket else aliceSocket
                val before = registry.peek(room.code)!!.state.value!!

                idleSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(
                        intent = PlayerIntent.Fold(seatIndex = idleSeat.index),
                        clientNonce = "oot-1",
                    ),
                )
                val ack = idleSocket.receiveUntilAck("oot-1")
                assertTrue(!ack.accepted, "an out-of-turn fold must be rejected")
                assertTrue(
                    ack.error?.contains("turn") == true,
                    "rejection should explain it's not their turn; was '${ack.error}'",
                )

                val after = registry.peek(room.code)!!.state.value!!
                assertEquals(before.street, after.street, "rejected intent must not advance the street")
                assertEquals(
                    before.actingSeatIndex,
                    after.actingSeatIndex,
                    "rejected intent must not move the action",
                )
                assertEquals(before.handNumber, after.handNumber)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    @Test
    fun submitIntent_duplicateNonce_isAcceptedTwice_butAppliedOnce() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withRoomSocketTestApp(rooms, registry) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                val actingSeat = opening.state.actingSeatIndex!!
                val actorUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val actorSocket = if (actorUser == host.value.toString()) hostSocket else aliceSocket
                val foldFrame = RoomClientFrame.SubmitIntent(
                    intent = PlayerIntent.Fold(seatIndex = actingSeat),
                    clientNonce = "dup",
                )

                actorSocket.sendFrame(foldFrame)
                assertTrue(actorSocket.receiveUntilAck("dup").accepted)
                actorSocket.receiveUntilGameState { it.street == BettingRound.Complete }
                val afterFirst = registry.peek(room.code)!!.state.value!!

                // Resubmit the same nonce — server dedupe returns Accepted
                // without re-running the engine.
                actorSocket.sendFrame(foldFrame)
                val secondAck = actorSocket.receiveUntilAck("dup")
                assertTrue(secondAck.accepted, "a duplicate nonce stays idempotently accepted")

                val afterSecond = registry.peek(room.code)!!.state.value!!
                assertEquals(
                    afterFirst.handNumber,
                    afterSecond.handNumber,
                    "duplicate intent must not advance the hand",
                )
                assertEquals(BettingRound.Complete, afterSecond.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // RequestNextHand
    // ===================================================================

    @Test
    fun requestNextHand_fromNonHost_advancesToNextHand() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withRoomSocketTestApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                // Complete hand one by folding the actor.
                val actingSeat = opening.state.actingSeatIndex!!
                val actorUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val actorSocket = if (actorUser == host.value.toString()) hostSocket else aliceSocket
                actorSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = actingSeat), "fold-1"),
                )
                assertTrue(actorSocket.receiveUntilAck("fold-1").accepted)
                actorSocket.receiveUntilGameState { it.street == BettingRound.Complete }

                // The non-host (Alice) advances — there's no host gate on
                // requestNextHand.
                aliceSocket.sendFrame(RoomClientFrame.RequestNextHand(clientNonce = "next-1"))
                assertTrue(aliceSocket.receiveUntilAck("next-1").accepted)

                val secondHand = aliceSocket.receiveUntilGameState { it.handNumber == 2 }
                assertEquals(2, secondHand.state.handNumber)
                assertEquals(BettingRound.Preflop, secondHand.state.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // Broadcast contract — scrubbing + sequencing
    // ===================================================================

    @Test
    fun gameStateSnapshot_isScrubbedPerRecipient() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withRoomSocketTestApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            try {
                hostSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)

                val snapshot = hostSocket.receiveUntilGameState().state
                val mySeat = snapshot.seats.single { it.playerId == host.value.toString() }
                val opponentSeat = snapshot.seats.single { it.playerId == alice.value.toString() }

                assertEquals(2, mySeat.holeCards.size, "viewer must see their own two hole cards")
                assertTrue(
                    opponentSeat.holeCards.isEmpty(),
                    "preflop opponent hole cards must be scrubbed for the viewer",
                )
            } finally {
                hostSocket.closeQuietly()
            }
        }
    }

    @Test
    fun gameEventOccurred_carriesMonotonicSequence() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")

        withRoomSocketTestApp(rooms) { client ->
            val hostSocket = client.connect(room.code, host)
            try {
                hostSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)

                // The opening burst (HandStarted, blinds, …) is several
                // events — collect a few and assert strict monotonicity.
                val seqs = buildList {
                    repeat(3) { add(hostSocket.receiveUntilEvent().seq) }
                }
                assertEquals(
                    seqs.sorted(),
                    seqs,
                    "event sequences must arrive non-decreasing; got $seqs",
                )
                assertEquals(
                    seqs.toSet().size,
                    seqs.size,
                    "event sequences must be distinct; got $seqs",
                )
            } finally {
                hostSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // Resilience
    // ===================================================================

    @Test
    fun socketDisconnect_midHand_engineContinues_forOtherSeat() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()

        withRoomSocketTestApp(rooms, registry) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                val opening = hostSocket.receiveUntilGameState()

                val actingSeat = opening.state.actingSeatIndex!!
                val actingUser = opening.state.seats.first { it.index == actingSeat }.playerId!!
                val idleUser = opening.state.seats.first { it.index != actingSeat }.playerId!!
                val actorSocket = if (actingUser == host.value.toString()) hostSocket else aliceSocket
                val idleSocket = if (idleUser == host.value.toString()) hostSocket else aliceSocket
                val idleUserId = if (idleUser == host.value.toString()) host else alice

                // The non-acting player's socket drops mid-hand.
                idleSocket.closeQuietly()
                awaitDisconnected(rooms, room.code, idleUserId)

                // The acting player's action still processes — the engine
                // isn't bound to any single connection.
                actorSocket.sendFrame(
                    RoomClientFrame.SubmitIntent(PlayerIntent.Fold(seatIndex = actingSeat), "fold-1"),
                )
                assertTrue(
                    actorSocket.receiveUntilAck("fold-1").accepted,
                    "the surviving socket's intent must still be processed",
                )
                assertEquals(BettingRound.Complete, registry.peek(room.code)!!.state.value!!.street)
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    // ===================================================================
    // Rebuy
    // ===================================================================

    /**
     * Inject a hand-Complete state where [bustedUser] has 0 chips and the host
     * still has chips — the window a rebuy acts in. Hydrated over the live
     * session after a real StartHand (which seeds the engine's settings).
     */
    private fun bustedCompleteState(bustedUser: UserId, otherUser: UserId, settings: com.dangerfield.cards.libraries.gameplay.RoomSettings) =
        GameState(
            settings = settings,
            handNumber = 2,
            buttonSeatIndex = 0,
            seats = listOf(
                Seat(
                    index = 0,
                    playerId = otherUser.value.toString(),
                    displayName = "Host",
                    stack = settings.startingStack * 2,
                    seatStatus = SeatStatus.Active,
                    handParticipation = HandParticipation.InHand,
                ),
                Seat(
                    index = 1,
                    playerId = bustedUser.value.toString(),
                    displayName = "Alice",
                    stack = 0,
                    seatStatus = SeatStatus.Active,
                    handParticipation = HandParticipation.Folded,
                ),
            ),
            community = emptyList(),
            street = BettingRound.Complete,
            currentBetThisStreet = 0,
            lastFullRaiseSize = 0,
            actingSeatIndex = null,
            deckRemaining = emptyList(),
        )

    @Test
    fun rebuy_withSufficientWallet_acceptsRefillsSeat_andDebitsBuyIn() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()
        val wallets = InMemoryTestWalletRepository()

        withRoomSocketTestApp(rooms, registry, wallets = wallets) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                hostSocket.receiveUntilGameState()

                val buyIn = rooms.find(room.code)!!.settings.startingStack
                registry.peek(room.code)!!.hydrate(
                    bustedCompleteState(alice, host, rooms.find(room.code)!!.settings),
                )
                wallets.setBalance(alice, buyIn)

                aliceSocket.sendFrame(RoomClientFrame.Rebuy(clientNonce = "r1"))
                val ack = aliceSocket.receiveUntilAck("r1")

                assertTrue(ack.accepted, "rebuy with enough chips should be accepted; error=${ack.error}")
                assertEquals(
                    buyIn,
                    registry.peek(room.code)!!.state.value!!.seats.first { it.playerId == alice.value.toString() }.stack,
                    "seat refilled to the buy-in",
                )
                assertEquals(0, wallets.balanceOf(alice), "wallet debited by exactly the buy-in")
                assertTrue(
                    wallets.applyCalls.any { it.delta == -buyIn && it.reason == "rebuy" },
                    "a debit for the buy-in was applied",
                )
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    @Test
    fun rebuy_withInsufficientWallet_isRejected_noStackChange_noNetDebit() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()
        val wallets = InMemoryTestWalletRepository()

        withRoomSocketTestApp(rooms, registry, wallets = wallets) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                hostSocket.receiveUntilGameState()

                val buyIn = rooms.find(room.code)!!.settings.startingStack
                registry.peek(room.code)!!.hydrate(
                    bustedCompleteState(alice, host, rooms.find(room.code)!!.settings),
                )
                wallets.setBalance(alice, buyIn - 1)

                aliceSocket.sendFrame(RoomClientFrame.Rebuy(clientNonce = "r1"))
                val ack = aliceSocket.receiveUntilAck("r1")

                assertFalse(ack.accepted, "rebuy must be rejected when the wallet can't cover the buy-in")
                assertTrue(ack.error?.contains("insufficient", ignoreCase = true) == true, "error: ${ack.error}")
                assertEquals(
                    0,
                    registry.peek(room.code)!!.state.value!!.seats.first { it.playerId == alice.value.toString() }.stack,
                    "seat stays busted",
                )
                assertEquals(buyIn - 1, wallets.balanceOf(alice), "balance unchanged — no debit committed")
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    @Test
    fun rebuy_whenHandInProgress_isRejected() = runTest {
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()
        val wallets = InMemoryTestWalletRepository()

        withRoomSocketTestApp(rooms, registry, wallets = wallets) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                hostSocket.receiveUntilGameState() // hand is live (not Complete)

                wallets.setBalance(alice, rooms.find(room.code)!!.settings.startingStack)
                aliceSocket.sendFrame(RoomClientFrame.Rebuy(clientNonce = "r1"))
                val ack = aliceSocket.receiveUntilAck("r1")

                assertFalse(ack.accepted, "can't rebuy mid-hand")
                assertEquals(0, wallets.applyCalls.size, "no wallet movement on a mid-hand rebuy")
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

    @Test
    fun rebuy_retryAfterSuccess_doesNotDoubleDebit() = runTest {
        // Once rebought the seat is no longer busted, so a redundant retry is
        // rejected by the pre-check before any wallet movement — the buy-in is
        // never charged twice.
        val rooms = newRoomService()
        val room = rooms.createOrFail(host, "Host", maxSeats = 4)
        rooms.join(room.code, alice, "Alice")
        val registry = newRegistry()
        val wallets = InMemoryTestWalletRepository()

        withRoomSocketTestApp(rooms, registry, wallets = wallets) { client ->
            val hostSocket = client.connect(room.code, host)
            val aliceSocket = client.connect(room.code, alice)
            try {
                hostSocket.drainSnapshot()
                aliceSocket.drainSnapshot()
                hostSocket.sendFrame(RoomClientFrame.StartHand(clientNonce = "s"))
                assertTrue(hostSocket.receiveUntilAck("s").accepted)
                hostSocket.receiveUntilGameState()

                val buyIn = rooms.find(room.code)!!.settings.startingStack
                registry.peek(room.code)!!.hydrate(
                    bustedCompleteState(alice, host, rooms.find(room.code)!!.settings),
                )
                wallets.setBalance(alice, buyIn + 500)

                aliceSocket.sendFrame(RoomClientFrame.Rebuy(clientNonce = "r1"))
                assertTrue(aliceSocket.receiveUntilAck("r1").accepted)
                aliceSocket.sendFrame(RoomClientFrame.Rebuy(clientNonce = "r2"))
                assertFalse(aliceSocket.receiveUntilAck("r2").accepted, "already rebought → rejected")

                assertEquals(500, wallets.balanceOf(alice), "charged exactly one buy-in across both attempts")
            } finally {
                hostSocket.closeQuietly()
                aliceSocket.closeQuietly()
            }
        }
    }

}

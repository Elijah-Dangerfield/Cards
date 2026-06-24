package com.dangerfield.cards.libraries.rooms.impl

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Test seam for [ReconnectingRoomSocket]. Lets a test prime the next
 * `open()` outcome (success / typed failure), inspect how many times
 * open was attempted, and pump inbound frames + observe outbound
 * frames + close calls on each session.
 *
 * The contract is intentionally minimal — production behavior under
 * Ktor (URL build, frame ↔ string mapping) is pinned in
 * [KtorRoomSocketTransportTest] instead.
 */
internal class FakeRoomSocketTransport : RoomSocketTransport {

    /** Total `open()` attempts seen by this transport. */
    var openCalls: Int = 0
        private set

    /** Room codes seen by `open()` in call order. */
    val openCodes: MutableList<String> = mutableListOf()

    /** All sessions handed back to the socket so tests can drive them. */
    val sessions: MutableList<FakeRoomSocketSession> = mutableListOf()

    /**
     * Queue of outcomes the next N `open()` calls will return, in
     * order. If the queue is empty when `open()` fires, defaults to a
     * fresh success session (the common case).
     */
    private val pendingOutcomes: ArrayDeque<suspend () -> Result<FakeRoomSocketSession>> =
        ArrayDeque()

    /** Latest live session (or null if none has been opened). */
    val latest: FakeRoomSocketSession? get() = sessions.lastOrNull()

    /** Queue an open() success that returns a fresh session. */
    fun primeSuccess(): FakeRoomSocketSession {
        val session = FakeRoomSocketSession()
        pendingOutcomes.addLast { Result.success(session) }
        return session
    }

    /** Queue an open() failure with the given throwable. */
    fun primeFailure(e: Throwable) {
        pendingOutcomes.addLast { Result.failure(e) }
    }

    /**
     * Queue an open() that suspends until [gate] completes, then
     * resolves to the given outcome. Useful for asserting that a
     * caller is parked waiting on the handshake.
     */
    fun primeGated(
        gate: kotlinx.coroutines.CompletableDeferred<Unit>,
        outcome: Result<FakeRoomSocketSession> = Result.success(FakeRoomSocketSession()),
    ) {
        pendingOutcomes.addLast {
            gate.await()
            outcome
        }
    }

    /**
     * Simulate a server-initiated reconnect: close the current live
     * session (the inbound flow completes, matching a real WS drop) and
     * queue the next [primeSuccess] so the production reconnect loop's
     * fresh `open()` lands on the returned session. Spells out the
     * close → reopen contract that tests previously had to assemble
     * from two raw helpers; the production [ReconnectingRoomSocket]
     * does exactly this every reconnect cycle, so the fake should
     * model it as one explicit call.
     */
    fun simulateReconnect(): FakeRoomSocketSession {
        latest?.closeFromPeer()
        return primeSuccess()
    }

    override suspend fun open(code: String): Result<RoomSocketSession> {
        openCalls += 1
        openCodes += code
        val producer = pendingOutcomes.removeFirstOrNull()
            ?: { Result.success(FakeRoomSocketSession()) }
        val outcome = producer()
        outcome.onSuccess { session ->
            sessions += session
        }
        return outcome
    }
}

/**
 * One controllable session. Tests push inbound frames through
 * [receive] / [receiveEvent], inspect outbound frames via [sent], and
 * mark the session terminally closed via [closeFromPeer] (the WS
 * dropped from the server side — `incoming` flow completes).
 */
internal class FakeRoomSocketSession : RoomSocketSession {

    private val inbox: Channel<String> = Channel(capacity = Channel.UNLIMITED)
    val sent: MutableList<String> = mutableListOf()
    var closed: Boolean = false
        private set

    override val incoming: Flow<String> = inbox.consumeAsFlow()

    override suspend fun send(text: String) {
        sent += text
    }

    override suspend fun close() {
        closed = true
        // Closing the inbox lets the production loop observe a clean
        // drop on `incoming.collect`, matching the contract.
        inbox.close()
    }

    /** Push a raw text frame to the production reader. */
    suspend fun receive(text: String) {
        inbox.send(text)
    }

    /** Push a typed server frame, encoded via the production JSON config. */
    suspend fun receive(event: RoomSocketEventDto) {
        inbox.send(RoomSocketJson.encodeToString(RoomSocketEventDto.serializer(), event))
    }

    /** Simulate a clean WS drop from the server side. */
    fun closeFromPeer() {
        inbox.close()
    }
}

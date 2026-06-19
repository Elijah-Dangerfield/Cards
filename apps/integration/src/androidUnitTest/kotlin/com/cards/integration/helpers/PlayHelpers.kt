package com.cards.integration.helpers

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.GameState
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.Seat
import com.dangerfield.cards.libraries.rooms.CreateRoomOutcome

/** Host creates a room, joiner joins it; returns the room code. */
suspend fun createAndJoin(host: TestClient, joiner: TestClient): String {
    val created = host.repository.createRoom()
    check(created is CreateRoomOutcome.Success) { "createRoom failed: $created" }
    joiner.repository.joinRoom(created.room.code)
    return created.room.code
}

/** The seat in this state owned by [client]. */
fun GameState.seatFor(client: TestClient): Seat = seats.first { it.playerId == client.userId }

/**
 * Two seated, connected clients and their gameplay sessions — the standard
 * heads-up table. Both are fault-capable so chaos tests can drop either.
 */
suspend fun Harness.seatTwoAndConnect(): Table {
    val host = client(faulty = true)
    val joiner = client(faulty = true)
    val code = createAndJoin(host, joiner)
    val hostGame = gameplay(host.connect(code))
    val joinerGame = gameplay(joiner.connect(code))
    hostGame.awaitConnected()
    joinerGame.awaitConnected()
    return Table(code, host, hostGame, joiner, joinerGame)
}

/** A two-player table plus convenience lookups from seat → owning client/session. */
class Table(
    val code: String,
    val host: TestClient,
    val hostGame: GameplaySession,
    val joiner: TestClient,
    val joinerGame: GameplaySession,
) {
    fun gameOf(client: TestClient): GameplaySession = if (client === host) hostGame else joinerGame

    fun other(client: TestClient): TestClient = if (client === host) joiner else host

    /** The client whose turn it is in [state]. */
    fun actingClient(state: GameState): TestClient {
        val playerId = state.seatAt(state.actingSeatIndex!!).playerId
        return if (playerId == host.userId) host else joiner
    }

    fun gameForSeat(state: GameState, seatIndex: Int): GameplaySession =
        if (state.seatAt(seatIndex).playerId == host.userId) hostGame else joinerGame
}

/**
 * Walk the hand forward over the wire with the most passive legal action
 * (check when nothing is owed, otherwise call) until it reaches [target],
 * returning the first snapshot on that street. Reads from the host's view
 * (bet sizes are public) and routes each action to the seat that owns it.
 * Each `nextSnapshot` advances the forward cursor, so the assertion always
 * lands on a post-action snapshot — production ordering, not a buffered one.
 */
suspend fun Table.advancePassivelyUntil(target: BettingRound, maxActions: Int = 60): GameState {
    var guard = 0
    while (guard++ < maxActions) {
        val s = hostGame.nextSnapshot { it.street == target || it.actingSeatIndex != null }
        if (s.street == target) return s
        actPassively(s)
    }
    error("hand did not reach $target within $maxActions actions")
}

/**
 * Play the hand to its [BettingRound.Complete] state passively (the called-
 * down line), returning the completed snapshot. Companion to
 * [advancePassivelyUntil] for the "all the way to showdown" case.
 */
suspend fun Table.playPassivelyToCompletion(maxActions: Int = 60): GameState {
    var guard = 0
    while (guard++ < maxActions) {
        val s = hostGame.nextSnapshot { it.street == BettingRound.Complete || it.actingSeatIndex != null }
        if (s.street == BettingRound.Complete) return s
        actPassively(s)
    }
    error("hand did not complete within $maxActions actions")
}

private suspend fun Table.actPassively(state: GameState) {
    val seatIndex = state.actingSeatIndex!!
    val seat = state.seatAt(seatIndex)
    val toCall = state.currentBetThisStreet - seat.contributedThisStreet
    val ack = gameForSeat(state, seatIndex).submit(
        if (toCall > 0) PlayerIntent.Call(seatIndex) else PlayerIntent.Check(seatIndex),
    )
    check(ack.accepted) { "passive action at seat $seatIndex (${state.street}) rejected: ${ack.error}" }
}

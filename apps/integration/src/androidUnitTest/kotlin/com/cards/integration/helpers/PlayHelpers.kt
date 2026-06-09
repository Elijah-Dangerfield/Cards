package com.cards.integration.helpers

import com.dangerfield.cards.libraries.gameplay.GameState
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

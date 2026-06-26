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

/**
 * Three seated, connected clients and their gameplay sessions — the table a
 * genuine multi-tier side pot needs (a short all-in plus two larger callers).
 * `seatTwoAndConnect` can't produce one: equal starting stacks means a single
 * shove is always covered evenly. Here we seat three so a prior-hand bleed can
 * leave one seat short before the all-in.
 */
suspend fun Harness.seatThreeAndConnect(): Trio {
    val host = client()
    val joinerA = client()
    val joinerB = client()
    val created = host.repository.createRoom(maxSeats = 3)
    check(created is CreateRoomOutcome.Success) { "createRoom failed: $created" }
    val code = created.room.code
    joinerA.repository.joinRoom(code)
    joinerB.repository.joinRoom(code)
    val games = listOf(host, joinerA, joinerB).map { it to gameplay(it.connect(code)) }
    games.forEach { (_, game) -> game.awaitConnected() }
    return Trio(code, games)
}

/**
 * A three-player table. [observer] reads public state (bet sizes, stacks); each
 * action routes to the seat that owns it via [gameForSeat], exactly as the
 * two-player [Table] does.
 */
class Trio(
    val code: String,
    private val games: List<Pair<TestClient, GameplaySession>>,
) {
    /** The session used to read public snapshots and drive hand lifecycle. */
    val observer: GameplaySession = games.first().second

    fun gameForSeat(state: GameState, seatIndex: Int): GameplaySession {
        val playerId = state.seatAt(seatIndex).playerId
        return games.first { (client, _) -> client.userId == playerId }.second
    }
}

/** The seat in this state owned by [client]. */
fun GameState.seatFor(client: TestClient): Seat = seats.first { it.playerId == client.userId }

/**
 * [humanCount] seated, connected humans in one Private room (host = first), all
 * sockets live and confirmed in the membership. Generalises [seatTwoAndConnect]
 * /[seatThreeAndConnect] with the clients exposed, for tests that drive specific
 * players (leave one, act another). [maxSeats] defaults to exactly [humanCount];
 * pass a larger value to leave room for mid-hand joiners.
 */
suspend fun Harness.seatPrivate(humanCount: Int, maxSeats: Int = humanCount): SeatedRoom {
    require(humanCount >= 1)
    val clients = List(humanCount) { client() }
    val created = clients.first().repository.createRoom(maxSeats = maxSeats)
    check(created is CreateRoomOutcome.Success) { "createRoom failed: $created" }
    val code = created.room.code
    clients.drop(1).forEach { it.repository.joinRoom(code) }
    val games = clients.map { gameplay(it.connect(code)) }
    games.forEach { it.awaitConnected() }
    games.first().awaitRoom { it.members.size == humanCount }
    return SeatedRoom(code, clients, games)
}

/** An N-human Private table with clients + sessions exposed for targeted control. */
class SeatedRoom(
    val code: String,
    val clients: List<TestClient>,
    val games: List<GameplaySession>,
) {
    val host: TestClient get() = clients.first()
    val hostGame: GameplaySession get() = games.first()

    fun gameOf(client: TestClient): GameplaySession = games[clients.indexOf(client)]

    /** playerId → session for exactly the listed clients (the actors a hand should use). */
    fun actableOf(vararg included: TestClient): Map<String, GameplaySession> =
        included.associate { it.userId to gameOf(it) }
}

/** The session owning the seat at [seatIndex] in [state]. */
fun SeatedRoom.gameForSeat(state: GameState, seatIndex: Int): GameplaySession {
    val playerId = state.seatAt(seatIndex).playerId
    return games[clients.indexOfFirst { it.userId == playerId }]
}

/**
 * Drive a hand to completion by submitting an explicit per-seat action each turn
 * — [action] is consulted with the acting seat + current state and returns that
 * seat's intent. Generalises [driveToCompletion]'s passive line to scripted ones
 * (shove here, fold there), which a scripted deck needs to force a chosen bust.
 * Returns the completed snapshot.
 */
suspend fun SeatedRoom.driveActions(
    maxActions: Int = 80,
    action: (seatIndex: Int, state: GameState) -> PlayerIntent,
): GameState {
    var guard = 0
    while (guard++ < maxActions) {
        val s = hostGame.nextSnapshot { it.street == BettingRound.Complete || it.actingSeatIndex != null }
        if (s.street == BettingRound.Complete) return s
        val acting = s.actingSeatIndex!!
        val ack = gameForSeat(s, acting).submit(action(acting, s))
        check(ack.accepted) { "scripted action at seat $acting (${s.street}) rejected: ${ack.error}" }
    }
    error("hand did not complete within $maxActions actions")
}

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

/**
 * Drive a heads-up all-in to showdown: the first seat to act shoves all-in, the
 * other calls, the board runs out. Pair with a scripted deck (`server.scriptDeck`
 * + `stackedDeck`) to bust a chosen player deterministically — the only way to
 * force a bust over the wire, since passive play never commits a full stack.
 * Returns the completed snapshot. Generalises `WireAllInTest`'s inline shove.
 */
suspend fun Table.shoveAllInHeadsUp(): GameState {
    val dealt = hostGame.nextSnapshot { it.actingSeatIndex != null }
    val shover = dealt.actingSeatIndex!!
    val shoveAck = gameForSeat(dealt, shover).submit(PlayerIntent.AllIn(seatIndex = shover))
    check(shoveAck.accepted) { "all-in shove rejected: ${shoveAck.error}" }

    val facing = hostGame.nextSnapshot { it.actingSeatIndex != null && it.actingSeatIndex != shover }
    val caller = facing.actingSeatIndex!!
    val callAck = gameForSeat(facing, caller).submit(PlayerIntent.Call(seatIndex = caller))
    check(callAck.accepted) { "calling the shove rejected: ${callAck.error}" }

    return hostGame.nextSnapshot { it.street == BettingRound.Complete }
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

/**
 * Drive a hand to [BettingRound.Complete] over the wire, acting only the seats
 * whose `playerId` is a key in [actable] (→ that player's session). Each acted
 * seat plays passively (call when something is owed, else check). Seats NOT in
 * [actable] — a folded/forfeited player, a bot the server drives — are never
 * acted on, so the caller controls exactly who's still playing. Reads forward
 * from [observer]; returns the completed snapshot.
 *
 * Generalises [Table.playPassivelyToCompletion] to N players with explicit
 * actor control — needed by forfeit/concurrency tests where one seat drops out
 * mid-hand and the rest must carry the hand to the end.
 */
suspend fun driveToCompletion(
    observer: GameplaySession,
    actable: Map<String, GameplaySession>,
    maxActions: Int = 80,
): GameState {
    var guard = 0
    while (guard++ < maxActions) {
        val s = observer.nextSnapshot { it.street == BettingRound.Complete || it.actingSeatIndex != null }
        if (s.street == BettingRound.Complete) return s
        val acting = s.actingSeatIndex!!
        val seat = s.seatAt(acting)
        val game = actable[seat.playerId]
        if (game == null) {
            // The acting seat isn't one we drive — a bot the server plays, or a
            // player mid-forfeit whose turn is about to advance. Wait for the
            // next snapshot rather than acting (or erroring) for them.
            continue
        }
        val toCall = s.currentBetThisStreet - seat.contributedThisStreet
        val ack = game.submit(if (toCall > 0) PlayerIntent.Call(acting) else PlayerIntent.Check(acting))
        check(ack.accepted) { "passive action at seat $acting (${s.street}) rejected: ${ack.error}" }
    }
    error("hand did not complete within $maxActions actions")
}

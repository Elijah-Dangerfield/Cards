package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Drives a real hand through [DefaultGameSessionRegistry] end-to-end:
 * register → start → observe state appearing → fold-to-complete → next
 * hand. This is the smoke test for "the registry actually behaves
 * like the socket route will call it"; the socket route adds its own
 * integration tests on top.
 */
class GameSessionRegistryIntegrationTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )
    private val alice = SeatOccupant(seatIndex = 0, userId = "alice", displayName = "Alice", isBot = false)
    private val bob = SeatOccupant(seatIndex = 1, userId = "bob", displayName = "Bob", isBot = false)

    @Test
    fun observeSession_emitsNull_beforeStart() = runTest {
        val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)

        assertNull(registry.observeSession("ROOM1").first())
    }

    @Test
    fun observeSession_emitsSession_afterStart() = runTest {
        val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)

        registry.startHand("ROOM1", listOf(alice, bob), settings)
        val session = registry.observeSession("ROOM1").first()

        assertNotNull(session)
        assertEquals(session, registry.peek("ROOM1"))
    }

    @Test
    fun observeSession_isCodeScoped() = runTest {
        val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)

        registry.startHand("ROOM_A", listOf(alice, bob), settings)

        val other = registry.observeSession("ROOM_B").firstOrNull()
        assertNull(other)
        val mine = registry.observeSession("ROOM_A").first()
        assertNotNull(mine)
    }

    @Test
    fun fullHand_fold_drivesStateToComplete_through_registry() = runTest {
        val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)

        val started = registry.startHand("ROOM1", listOf(alice, bob), settings)
        assertIs<IntentResult.Accepted>(started)

        val session = registry.peek("ROOM1")!!
        val state = session.state.value!!
        val actingSeat = state.seats.first { it.index == state.actingSeatIndex }
        val actingUser = actingSeat.playerId!!

        val foldResult = registry.applyIntent(
            code = "ROOM1",
            actorUserId = actingUser,
            intent = PlayerIntent.Fold(seatIndex = actingSeat.index),
            clientNonce = "fold-1",
        )
        assertIs<IntentResult.Accepted>(foldResult)

        assertEquals(BettingRound.Complete, session.state.value!!.street)
    }

    @Test
    fun finishedHand_witnessesCountForRealUserIds_andKeysPerHand() = runTest {
        val counter = InMemoryHandsFinishedRepository()
        val registry = DefaultGameSessionRegistry(
            snapshotStore = NoOpSessionSnapshotStore(),
            clock = kotlin.time.Clock.System,
            handsFinishedRepository = counter,
        )
        val aliceId = java.util.UUID.randomUUID().toString()
        val bobId = java.util.UUID.randomUUID().toString()
        val aliceU = com.dangerfield.cards.server.domain.UserId(java.util.UUID.fromString(aliceId))
        val bobU = com.dangerfield.cards.server.domain.UserId(java.util.UUID.fromString(bobId))
        val a = SeatOccupant(seatIndex = 0, userId = aliceId, displayName = "Alice", isBot = false)
        val b = SeatOccupant(seatIndex = 1, userId = bobId, displayName = "Bob", isBot = false)

        registry.startHand("ROOM1", listOf(a, b), settings)
        val session = registry.peek("ROOM1")!!

        suspend fun foldActor(nonce: String) {
            val acting = session.state.value!!.actingSeatIndex!!
            val actor = session.state.value!!.seats.first { it.index == acting }
            registry.applyIntent("ROOM1", actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), nonce)
        }

        foldActor("fold-1")
        assertEquals(1L, counter.countForUser(aliceU))
        assertEquals(1L, counter.countForUser(bobU))

        // A second completed hand carries a distinct per-hand idempotency
        // key, so both counts advance to 2 (no collapse onto hand 1's key).
        registry.requestNextHand("ROOM1", aliceId, "next-1")
        foldActor("fold-2")
        assertEquals(2L, counter.countForUser(aliceU))
        assertEquals(2L, counter.countForUser(bobU))
    }

    @Test
    fun finishedHand_evaluatesServerWitnessedAchievements_forEachRealUser() = runTest {
        val evaluations = mutableListOf<com.dangerfield.cards.server.domain.UserId>()
        val perHandEvaluations =
            mutableListOf<Pair<com.dangerfield.cards.server.domain.UserId, com.dangerfield.cards.server.domain.PlayerHandOutcome>>()
        val recording = object : com.dangerfield.cards.server.domain.ServerWitnessedAchievements {
            override suspend fun evaluate(userId: com.dangerfield.cards.server.domain.UserId) {
                evaluations += userId
            }

            override suspend fun evaluateHand(
                userId: com.dangerfield.cards.server.domain.UserId,
                outcome: com.dangerfield.cards.server.domain.PlayerHandOutcome,
            ) {
                perHandEvaluations += userId to outcome
            }
        }
        val registry = DefaultGameSessionRegistry(
            snapshotStore = NoOpSessionSnapshotStore(),
            clock = kotlin.time.Clock.System,
            handsFinishedRepository = InMemoryHandsFinishedRepository(),
            serverWitnessedAchievements = recording,
        )
        val aliceId = java.util.UUID.randomUUID().toString()
        val bobId = java.util.UUID.randomUUID().toString()
        val a = SeatOccupant(seatIndex = 0, userId = aliceId, displayName = "Alice", isBot = false)
        val b = SeatOccupant(seatIndex = 1, userId = bobId, displayName = "Bob", isBot = false)

        registry.startHand("ROOM1", listOf(a, b), settings)
        val session = registry.peek("ROOM1")!!
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }
        registry.applyIntent("ROOM1", actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "fold-1")

        assertEquals(
            setOf(aliceId, bobId),
            evaluations.map { it.value.toString() }.toSet(),
        )
        // The per-hand path is driven for each real user off the same hand.
        assertEquals(
            setOf(aliceId, bobId),
            perHandEvaluations.map { it.first.value.toString() }.toSet(),
        )
        // The hand ended on a fold, so exactly one player took the pot without
        // a showdown — the `wonByFold` signal derived from the HandEnded event.
        assertEquals(
            1,
            perHandEvaluations.count { it.second.wonByFold },
            "the lone non-folder is credited a win by fold",
        )
    }

    @Test
    fun applyIntent_unknownCode_isRejected() = runTest {
        val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)

        val result = registry.applyIntent(
            code = "GHOST",
            actorUserId = "alice",
            intent = PlayerIntent.Fold(seatIndex = 0),
            clientNonce = "n1",
        )

        assertIs<IntentResult.Rejected>(result)
        assertEquals("no game session for room GHOST", result.reason)
    }

    @Test
    fun end_dropsSession_andSubsequentLookupReturnsNull() = runTest {
        val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)
        registry.startHand("ROOM1", listOf(alice, bob), settings)
        assertNotNull(registry.peek("ROOM1"))

        registry.end("ROOM1")

        assertNull(registry.peek("ROOM1"))
        assertNull(registry.observeSession("ROOM1").first())
    }

    @Test
    fun nextHand_continues_after_completion() = runTest {
        val registry = DefaultGameSessionRegistry(NoOpSessionSnapshotStore(), kotlin.time.Clock.System)
        registry.startHand("ROOM1", listOf(alice, bob), settings)

        val session = registry.peek("ROOM1")!!
        val acting = session.state.value!!.actingSeatIndex!!
        val actor = session.state.value!!.seats.first { it.index == acting }
        registry.applyIntent("ROOM1", actor.playerId!!, PlayerIntent.Fold(seatIndex = acting), "n1")
        assertEquals(BettingRound.Complete, session.state.value!!.street)

        val next = registry.requestNextHand("ROOM1", actor.playerId!!, "n2")
        assertIs<IntentResult.Accepted>(next)
        assertEquals(2, session.state.value!!.handNumber)
        assertEquals(BettingRound.Preflop, session.state.value!!.street)
    }
}

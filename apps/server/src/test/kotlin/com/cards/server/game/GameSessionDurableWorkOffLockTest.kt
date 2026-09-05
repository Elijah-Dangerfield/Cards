package com.dangerfield.cards.server.game

import com.dangerfield.cards.libraries.gameplay.BettingRound
import com.dangerfield.cards.libraries.gameplay.PlayerIntent
import com.dangerfield.cards.libraries.gameplay.RoomSettings
import com.dangerfield.cards.server.domain.HandsFinishedRepository
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The room's mutex serialises every action at a table, so anything slow
 * that runs while it's held stalls every player, not just the one who
 * acted. In prod this showed up as a `state_mutate` p99 of 5.1s against
 * an `engine.apply_intent` max of 33ms: the hand-ending action was
 * paying for a serial chain of Supabase writes (finished-hand counters,
 * achievement grants, the recently-played-with shelf) before releasing
 * the lock.
 *
 * None of that work is load-bearing for gameplay — every call site is
 * already best-effort, wrapped in `Catching` and logged rather than
 * surfaced. These tests pin it to the background, where a slow or
 * wedged Supabase can't reach the table.
 */
class GameSessionDurableWorkOffLockTest {

    private val settings = RoomSettings(
        smallBlind = 5,
        bigBlind = 10,
        startingStack = 1_000,
        maxSeats = 6,
        turnTimerSeconds = 30,
    )
    private val alice = SeatOccupant(seatIndex = 0, userId = ALICE, displayName = "Alice", isBot = false)
    private val bob = SeatOccupant(seatIndex = 1, userId = BOB, displayName = "Bob", isBot = false)

    /** Blocks forever inside the finished-hand write, standing in for a wedged Supabase. */
    private class WedgedHandsFinishedRepository : HandsFinishedRepository {
        val entered = CompletableDeferred<Unit>()
        private val never = CompletableDeferred<Unit>()

        override suspend fun recordHandFinished(
            userId: UserId,
            idempotencyKey: String,
            handSessionId: UUID,
            handNumber: Int,
            bustsDealt: Int,
            wonByFold: Boolean,
        ) {
            entered.complete(Unit)
            never.await()
        }

        override suspend fun countForUser(userId: UserId): Long = 0
        override suspend fun bustsDealtForUser(userId: UserId): Long = 0
        override suspend fun winsByFoldForUser(userId: UserId): Long = 0
        override suspend fun deleteAllForUser(userId: UserId) = Unit
    }

    @Test
    fun handEndingIntent_returns_whileFinishedHandBookkeepingIsStillRunning() = runTest {
        val wedged = WedgedHandsFinishedRepository()
        val registry = DefaultGameSessionRegistry(
            snapshotStore = NoOpSessionSnapshotStore(),
            clock = Clock.System,
            handsFinishedRepository = wedged,
            botDriverScope = backgroundScope,
        )
        registry.startHand(ROOM, listOf(alice, bob), settings)

        val result = withTimeout(10.seconds) { foldToEndTheHand(registry) }

        assertIs<IntentResult.Accepted>(result)
        // The bookkeeping really did run — this isn't passing because the
        // hook never fired.
        withTimeout(10.seconds) { wedged.entered.await() }
    }

    @Test
    fun tableKeepsMoving_whileFinishedHandBookkeepingIsWedged() = runTest {
        val wedged = WedgedHandsFinishedRepository()
        val registry = DefaultGameSessionRegistry(
            snapshotStore = NoOpSessionSnapshotStore(),
            clock = Clock.System,
            handsFinishedRepository = wedged,
            botDriverScope = backgroundScope,
        )
        registry.startHand(ROOM, listOf(alice, bob), settings)
        foldToEndTheHand(registry)
        withTimeout(10.seconds) { wedged.entered.await() }

        // Bookkeeping for hand 1 is still stuck in Supabase. Dealing hand 2
        // takes the same session mutex, so it is the direct probe for "is the
        // table still usable?" — before this change it deadlocked behind the
        // wedged write.
        val next = withTimeout(10.seconds) {
            registry.requestNextHand(code = ROOM, actorUserId = ALICE, clientNonce = "next-hand")
        }

        assertIs<IntentResult.Accepted>(next)
    }

    /** Folds whoever is on the button until the hand resolves; returns the closing result. */
    private suspend fun foldToEndTheHand(registry: DefaultGameSessionRegistry): IntentResult {
        var last: IntentResult = IntentResult.Rejected("no hand in progress")
        while (true) {
            val state = registry.peek(ROOM)?.state?.value ?: break
            if (state.street == BettingRound.Complete) break
            val acting = state.actingSeatIndex ?: break
            val actor = state.seats.first { it.index == acting }
            last = registry.applyIntent(
                code = ROOM,
                actorUserId = actor.playerId!!,
                intent = PlayerIntent.Fold(seatIndex = acting),
                clientNonce = "fold-$acting-${state.handNumber}",
            )
        }
        return last
    }

    private companion object {
        const val ROOM = "ROOM1"

        // Real UUIDs: recordHandsFinished skips non-UUID ids, so string
        // handles would make the wedged repository unreachable and the test
        // would pass for the wrong reason.
        val ALICE: String = UUID.randomUUID().toString()
        val BOB: String = UUID.randomUUID().toString()
    }
}

package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.PlayStyleEventsTable
import com.dangerfield.cards.server.db.UserPlayStyleAggregateTable
import com.dangerfield.cards.server.domain.PlayStyleHand
import com.dangerfield.cards.server.domain.UserId
import com.dangerfield.cards.server.domain.UserPlayStyleAggregate
import com.dangerfield.cards.server.domain.toAxes
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed play-style repo (real Postgres via
 * testcontainers), mirroring [PostgresProgressionRepositoryTest]. Covers the
 * `(user_id, idempotency_key)` PK dedup, rolling-counter accumulation, lazy
 * create, and the pure axis math.
 */
@OptIn(ExperimentalTime::class)
class PostgresPlayStyleRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            PlayStyleEventsTable.deleteAll()
            UserPlayStyleAggregateTable.deleteAll()
        }
    }

    @Test
    fun applyHand_accumulatesCounters() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHand(user, hand(key = "h1", vpip = true, pfr = true, aggressive = 2, calls = 1))
        repo.applyHand(user, hand(key = "h2", inBlind = true, preflopFold = true))

        val agg = repo.findOrCreateAggregate(user)
        assertEquals(2, agg.handsDealt)
        assertEquals(1, agg.handsDealtNonBlind) // h2 was a blind hand
        assertEquals(1, agg.vpipCount)
        assertEquals(1, agg.pfrCount)
        assertEquals(1, agg.preflopFoldCount)
        assertEquals(2, agg.aggressiveActionCount)
        assertEquals(1, agg.callActionCount)
    }

    @Test
    fun applyHand_isIdempotentOnKey() = runTest {
        val repo = newRepo()
        val user = newUser()

        val first = repo.applyHand(user, hand(key = "dup", vpip = true))
        val replay = repo.applyHand(user, hand(key = "dup", vpip = true))

        assertTrue(!first.wasAlreadyApplied)
        assertTrue(replay.wasAlreadyApplied)
        // The replay must not double-count.
        assertEquals(1, repo.findOrCreateAggregate(user).handsDealt)
    }

    @Test
    fun toAxes_computesStandardPokerMetrics() {
        // 10 dealt, 8 non-blind, 2 VPIP, 1 PFR, 5 preflop folds, AF = 6/3 = 2,
        // 4 showdowns / 1 bluff.
        val axes = aggregate(
            handsDealt = 10, nonBlind = 8, vpip = 2, pfr = 1, preflopFold = 5,
            aggressive = 6, calls = 3, showdown = 4, showdownBluff = 1,
        ).toAxes()

        assertEquals(0.75, axes.tight, 1e-9)      // 1 - 2/8
        assertEquals(0.5, axes.aggro, 1e-9)        // AF=2 → 2/(2+2)
        assertEquals(0.25, axes.bluff, 1e-9)       // 1/4
        assertEquals(0.625, axes.patient, 1e-9)    // 5/8
        assertEquals(10, axes.sampleSize)
    }

    @Test
    fun toAxes_emptyAggregate_isStable() {
        val axes = aggregate().toAxes()
        assertEquals(0, axes.sampleSize)
        assertEquals(1.0, axes.tight, 1e-9) // no VPIP → fully tight (gated by sampleSize client-side)
    }

    private fun hand(
        key: String,
        mode: String = "BOTS",
        inBlind: Boolean = false,
        vpip: Boolean = false,
        pfr: Boolean = false,
        preflopFold: Boolean = false,
        aggressive: Int = 0,
        calls: Int = 0,
        showdown: Boolean = false,
        showdownBluff: Boolean = false,
    ) = PlayStyleHand(
        idempotencyKey = key,
        mode = mode,
        inBlind = inBlind,
        vpip = vpip,
        pfr = pfr,
        preflopFold = preflopFold,
        aggressiveActionCount = aggressive,
        callActionCount = calls,
        wentToShowdown = showdown,
        showdownBluff = showdownBluff,
    )

    private fun aggregate(
        handsDealt: Long = 0,
        nonBlind: Long = 0,
        vpip: Long = 0,
        pfr: Long = 0,
        preflopFold: Long = 0,
        aggressive: Long = 0,
        calls: Long = 0,
        showdown: Long = 0,
        showdownBluff: Long = 0,
    ) = UserPlayStyleAggregate(
        userId = UserId.parse("00000000-0000-0000-0000-000000000000"),
        handsDealt = handsDealt,
        handsDealtNonBlind = nonBlind,
        vpipCount = vpip,
        pfrCount = pfr,
        preflopFoldCount = preflopFold,
        aggressiveActionCount = aggressive,
        callActionCount = calls,
        showdownCount = showdown,
        showdownBluffCount = showdownBluff,
        createdAt = Instant.fromEpochMilliseconds(0),
        updatedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun newRepo(clock: Clock = Clock.System): PostgresPlayStyleRepository =
        PostgresPlayStyleRepository(database = database, clock = clock)

    private fun newUser(): UserId = seedAuthUser()
}

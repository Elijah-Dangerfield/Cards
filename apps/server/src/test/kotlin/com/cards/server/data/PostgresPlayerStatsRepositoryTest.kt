package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.PlayerStatEventsTable
import com.dangerfield.cards.server.db.UserPlayerStatsTable
import com.dangerfield.cards.server.domain.PlayerStatHand
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Integration tests for the Postgres-backed player-stats repo (real Postgres
 * via testcontainers), mirroring [PostgresPlayStyleRepositoryTest]. Covers the
 * `(user_id, idempotency_key)` PK dedup, rolling-counter accumulation, the
 * per-bot win map, and the streak (latest-current + running-max-best) folding.
 */
@OptIn(ExperimentalTime::class)
class PostgresPlayerStatsRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction {
            PlayerStatEventsTable.deleteAll()
            UserPlayerStatsTable.deleteAll()
        }
    }

    @Test
    fun applyHand_accumulatesCounters() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHand(user, hand(key = "h1", won = true, vsBot = true, beatenBotId = "bot_shark", noBustStreak = 1))
        repo.applyHand(user, hand(key = "h2", folded = true, noBustStreak = 2))
        repo.applyHand(user, hand(key = "h3", lostAtShowdown = true, vsBot = true, noBustStreak = 0))

        val stats = repo.findOrCreate(user)
        assertEquals(3, stats.handsPlayed)
        assertEquals(1, stats.handsWon)
        assertEquals(1, stats.handsFolded)
        assertEquals(1, stats.handsLostAtShowdown)
        assertEquals(2, stats.botHandsPlayed)
        assertEquals(mapOf("bot_shark" to 1L), stats.perBotWins)
    }

    @Test
    fun applyHand_streakTakesLatestCurrentAndRunningMaxBest() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHand(user, hand(key = "h1", noBustStreak = 3))
        repo.applyHand(user, hand(key = "h2", noBustStreak = 5))
        // A bust resets the client streak to 0; current follows it, best holds.
        repo.applyHand(user, hand(key = "h3", noBustStreak = 0))

        val stats = repo.findOrCreate(user)
        assertEquals(0, stats.currentNoBustStreak)
        assertEquals(5, stats.bestNoBustStreak)
    }

    @Test
    fun applyHand_perBotWins_accumulatePerKey() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHand(user, hand(key = "h1", won = true, vsBot = true, beatenBotId = "bot_a", noBustStreak = 1))
        repo.applyHand(user, hand(key = "h2", won = true, vsBot = true, beatenBotId = "bot_a", noBustStreak = 2))
        repo.applyHand(user, hand(key = "h3", won = true, vsBot = true, beatenBotId = "bot_b", noBustStreak = 3))

        assertEquals(mapOf("bot_a" to 2L, "bot_b" to 1L), repo.findOrCreate(user).perBotWins)
    }

    @Test
    fun applyHand_isIdempotentOnKey() = runTest {
        val repo = newRepo()
        val user = newUser()

        val first = repo.applyHand(user, hand(key = "dup", won = true, vsBot = true, beatenBotId = "bot_a", noBustStreak = 1))
        val replay = repo.applyHand(user, hand(key = "dup", won = true, vsBot = true, beatenBotId = "bot_a", noBustStreak = 1))

        assertTrue(!first.wasAlreadyApplied)
        assertTrue(replay.wasAlreadyApplied)
        val stats = repo.findOrCreate(user)
        assertEquals(1, stats.handsPlayed)
        assertEquals(mapOf("bot_a" to 1L), stats.perBotWins)
    }

    @Test
    fun find_returnsNull_beforeAnyHand() = runTest {
        assertEquals(null, newRepo().find(newUser()))
    }

    private fun hand(
        key: String,
        mode: String = "BOTS",
        won: Boolean = false,
        folded: Boolean = false,
        lostAtShowdown: Boolean = false,
        vsBot: Boolean = false,
        beatenBotId: String? = null,
        noBustStreak: Long = 0,
    ) = PlayerStatHand(
        idempotencyKey = key,
        mode = mode,
        won = won,
        folded = folded,
        lostAtShowdown = lostAtShowdown,
        vsBot = vsBot,
        beatenBotId = beatenBotId,
        noBustStreak = noBustStreak,
    )

    private fun newRepo(clock: Clock = Clock.System): PostgresPlayerStatsRepository =
        PostgresPlayerStatsRepository(database = database, clock = clock)

    private fun newUser(): UserId = seedAuthUser()
}

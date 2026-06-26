package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.achievements.AchievementCounters
import com.dangerfield.cards.libraries.achievements.HandFacts
import com.dangerfield.cards.libraries.achievements.ShownHand
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.PlayerStatEventsTable
import com.dangerfield.cards.server.db.UserPlayerStatsTable
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

        repo.applyHand(user, hand(key = "h1", won = true, vsBot = true, beatenBotId = "bot_shark"))
        repo.applyHand(user, hand(key = "h2", folded = true))
        repo.applyHand(user, hand(key = "h3", lostAtShowdown = true, vsBot = true))

        val stats = repo.findOrCreate(user)
        assertEquals(3, stats.handsPlayed)
        assertEquals(1, stats.handsWon)
        assertEquals(1, stats.handsFolded)
        assertEquals(1, stats.handsLostAtShowdown)
        assertEquals(2, stats.botHandsPlayed)
        assertEquals(mapOf("bot_shark" to 1L), stats.perBotWins)
    }

    @Test
    fun applyHand_derivesStreakFromBustFacts_currentAndRunningMaxBest() = runTest {
        val repo = newRepo()
        val user = newUser()

        // The server DERIVES the streak from the per-hand bust fact (ordered fold),
        // never from a client-sent snapshot — so a reinstalled client can't clobber it.
        repo.applyHand(user, hand(key = "h1"))                 // streak 1
        repo.applyHand(user, hand(key = "h2"))                 // streak 2
        repo.applyHand(user, hand(key = "h3"))                 // streak 3
        repo.applyHand(user, hand(key = "h4", busted = true))  // reset to 0
        repo.applyHand(user, hand(key = "h5"))                 // streak 1

        val stats = repo.findOrCreate(user)
        assertEquals(1, stats.currentNoBustStreak, "current follows the latest run")
        assertEquals(3, stats.bestNoBustStreak, "best holds the high-water before the bust")
    }

    @Test
    fun applyHand_foldsRichAchievementCounters() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHand(
            user,
            hand(
                key = "h1", won = true, vsBot = true, beatenBotId = "Jane",
                botDifficulty = "Challenging", startStack = 1_000, endStack = 3_000,
                bigBlind = 20, potTotal = 1_000, wasAllIn = true, bustsDealt = 1,
                handStrengthShown = ShownHand.Flush.name,
            ),
        )

        val c = repo.findOrCreate(user).counters
        assertEquals(1, c[AchievementCounters.ALL_IN_HANDS])
        assertEquals(1, c[AchievementCounters.DOUBLED_UP])
        assertEquals(1, c[AchievementCounters.TRIPLED_UP])
        assertEquals(1_000, c[AchievementCounters.MAX_POT_SEEN])
        assertEquals(50, c[AchievementCounters.MAX_POT_BB_RATIO])
        assertEquals(1, c[AchievementCounters.CHALLENGING_WINS])
        assertEquals(1, c[AchievementCounters.BUSTS_DEALT])
        assertEquals(1, c[AchievementCounters.winsVsBot("Jane")])
        assertEquals(1, c[AchievementCounters.showKey(ShownHand.Flush)])
        assertEquals(1, c[AchievementCounters.showKey(ShownHand.Pair)], "a flush counts toward show-a-pair too")
    }

    @Test
    fun counters_surviveReplay_andRoundTripThroughJsonb() = runTest {
        val repo = newRepo()
        val user = newUser()
        val h = hand(key = "dup", wasAllIn = true, potTotal = 800, bigBlind = 10)

        repo.applyHand(user, h)
        repo.applyHand(user, h) // idempotent replay must not double-count the counter

        val c = repo.findOrCreate(user).counters
        assertEquals(1, c[AchievementCounters.ALL_IN_HANDS])
        assertEquals(80, c[AchievementCounters.MAX_POT_BB_RATIO])
    }

    @Test
    fun applyHand_perBotWins_accumulatePerKey() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHand(user, hand(key = "h1", won = true, vsBot = true, beatenBotId = "bot_a"))
        repo.applyHand(user, hand(key = "h2", won = true, vsBot = true, beatenBotId = "bot_a"))
        repo.applyHand(user, hand(key = "h3", won = true, vsBot = true, beatenBotId = "bot_b"))

        assertEquals(mapOf("bot_a" to 2L, "bot_b" to 1L), repo.findOrCreate(user).perBotWins)
    }

    @Test
    fun applyHand_isIdempotentOnKey() = runTest {
        val repo = newRepo()
        val user = newUser()

        val first = repo.applyHand(user, hand(key = "dup", won = true, vsBot = true, beatenBotId = "bot_a"))
        val replay = repo.applyHand(user, hand(key = "dup", won = true, vsBot = true, beatenBotId = "bot_a"))

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

    @Suppress("LongParameterList")
    private fun hand(
        key: String,
        mode: String = "BOTS",
        won: Boolean = false,
        folded: Boolean = false,
        lostAtShowdown: Boolean = false,
        vsBot: Boolean = false,
        beatenBotId: String? = null,
        busted: Boolean = false,
        botDifficulty: String? = null,
        startStack: Long = 0,
        endStack: Long = 0,
        bigBlind: Long = 0,
        potTotal: Long = 0,
        wasAllIn: Boolean = false,
        bustsDealt: Int = 0,
        handStrengthShown: String? = null,
    ) = HandFacts(
        idempotencyKey = key,
        mode = mode,
        won = won,
        folded = folded,
        lostAtShowdown = lostAtShowdown,
        vsBot = vsBot,
        beatenBotId = beatenBotId,
        busted = busted,
        botDifficulty = botDifficulty,
        startStack = startStack,
        endStack = endStack,
        bigBlind = bigBlind,
        potTotal = potTotal,
        wasAllIn = wasAllIn,
        bustsDealt = bustsDealt,
        handStrengthShown = handStrengthShown,
    )

    private fun newRepo(clock: Clock = Clock.System): PostgresPlayerStatsRepository =
        PostgresPlayerStatsRepository(database = database, clock = clock)

    private fun newUser(): UserId = seedAuthUser()
}

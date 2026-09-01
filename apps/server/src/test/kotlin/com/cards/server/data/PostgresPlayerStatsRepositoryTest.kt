package com.dangerfield.cards.server.data

import com.dangerfield.cards.libraries.achievements.AchievementCounters
import com.dangerfield.cards.libraries.achievements.HandFacts
import com.dangerfield.cards.libraries.achievements.ShownHand
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.db.PlayerStatEventsTable
import com.dangerfield.cards.server.db.SqlActivity
import com.dangerfield.cards.server.db.UserPlayerStatsTable
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime

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

    @Test
    fun applyHandBatch_twoThousandHands_costOneTransactionAndAHandfulOfStatements() = runTest {
        // ENG-47: the old path opened a transaction and issued ~4 statements per
        // hand, so a backlog flush spent 87s server-side and timed the client
        // out at 30s. The cost of a batch must not scale with its size.
        val repo = newRepo()
        val user = newUser()
        val hands = (1..2_000).map { hand(key = "k$it", won = it % 3 == 0, vsBot = true) }

        SqlActivity.reset()
        val elapsed = measureTime { repo.applyHandBatch(user, hands) }

        assertEquals(1, SqlActivity.commitCount, "the whole batch commits once")
        assertTrue(
            SqlActivity.statementCount <= 16,
            "2,000 hands must not cost 2,000 round trips (issued ${SqlActivity.statementCount})",
        )
        assertEquals(2_000, repo.findOrCreate(user).handsPlayed)
        assertTrue(elapsed < 10.seconds, "a full backlog flush stays well inside the client's timeout (took $elapsed)")
    }

    /**
     * The counter fold is order-dependent — the streak resets, the best-streak
     * high-water mark and the short-stack latch all read the previous hand's
     * value. Folding a batch as an unordered sum would corrupt every one of
     * them, so the batch must replay its hands in the order the client sent.
     */
    @Test
    fun applyHandBatch_foldsInArrivalOrder_notAsASum() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHandBatch(
            user,
            listOf(
                hand(key = "h1"),                // streak 1
                hand(key = "h2"),                // streak 2
                hand(key = "h3"),                // streak 3
                hand(key = "h4", busted = true), // reset to 0
                hand(key = "h5"),                // streak 1
            ),
        )

        val stats = repo.findOrCreate(user)
        assertEquals(1, stats.currentNoBustStreak, "current follows the latest run")
        assertEquals(3, stats.bestNoBustStreak, "best holds the high-water before the bust")
    }

    /**
     * The batch inserted in one statement, but each ledger row still carries the
     * streak as of *its* hand. A single value stamped across the whole batch
     * would pass the aggregate assertions above and still be wrong here.
     */
    @Test
    fun applyHandBatch_stampsEachLedgerRowWithItsOwnStreak() = runTest {
        val repo = newRepo()
        val user = newUser()

        repo.applyHandBatch(
            user,
            listOf(
                hand(key = "h1"),
                hand(key = "h2"),
                hand(key = "h3"),
                hand(key = "h4", busted = true),
                hand(key = "h5"),
            ),
        )

        assertEquals(mapOf("h1" to 1L, "h2" to 2L, "h3" to 3L, "h4" to 0L, "h5" to 1L), streaksFor(user))
    }

    /**
     * The strongest guard available: whatever a sequence of hands means when
     * applied one at a time is what it has to mean in one batch. Covers the
     * latch and the high-water marks without enumerating them.
     */
    @Test
    fun applyHandBatch_matchesHandByHandApplication() = runTest {
        val repo = newRepo()
        val batched = newUser()
        val oneAtATime = newUser()
        val hands = mixedHands()

        repo.applyHandBatch(batched, hands)
        hands.forEach { repo.applyHand(oneAtATime, it) }

        assertEquals(
            repo.findOrCreate(oneAtATime).counters,
            repo.findOrCreate(batched).counters,
            "batching must not change what a sequence of hands folds to",
        )
    }

    /**
     * A partial replay is the normal case after a timed-out flush: the client
     * re-sends hands the server already has, alongside new ones. Only the new
     * ones may move the fold, and they must fold onto the *stored* counters,
     * not onto a re-count of the replays.
     */
    @Test
    fun applyHandBatch_partialReplay_foldsOnlyTheNewHands() = runTest {
        val repo = newRepo()
        val user = newUser()
        val first = listOf(hand(key = "h1"), hand(key = "h2"), hand(key = "h3"))
        repo.applyHandBatch(user, first)

        val result = repo.applyHandBatch(user, first + listOf(hand(key = "h4"), hand(key = "h5")))

        assertEquals(setOf("h4", "h5"), result.appliedKeys)
        assertEquals(5, result.stats.handsPlayed, "the replayed hands are not counted twice")
        assertEquals(5, result.stats.currentNoBustStreak, "the new hands continue the stored streak")
        assertEquals(mapOf("h4" to 4L, "h5" to 5L), streaksFor(user).filterKeys { it in setOf("h4", "h5") })
    }

    @Test
    fun applyHandBatch_fullReplay_movesNothing() = runTest {
        val repo = newRepo()
        val user = newUser()
        val hands = (1..50).map { hand(key = "replay$it", won = true, vsBot = true, beatenBotId = "bot_a") }
        repo.applyHandBatch(user, hands)

        val replay = repo.applyHandBatch(user, hands)

        assertTrue(replay.appliedKeys.isEmpty(), "a full replay commits nothing")
        assertEquals(50, replay.stats.handsPlayed, "a full replay moves the counters by zero")
        assertEquals(mapOf("bot_a" to 50L), replay.stats.perBotWins)
    }

    @Test
    fun applyHandBatch_repeatedKeyInsideOneBatch_countsOnce() = runTest {
        val repo = newRepo()
        val user = newUser()

        val result = repo.applyHandBatch(user, listOf(hand(key = "dupe"), hand(key = "dupe")))

        assertEquals(setOf("dupe"), result.appliedKeys)
        assertEquals(1, result.stats.handsPlayed, "a key repeated inside one payload counts once")
        assertEquals(1, result.stats.currentNoBustStreak, "and only advances the streak once")
    }

    @Test
    fun applyHandBatch_emptyBatch_lazyCreatesAndReportsTheCurrentStats() = runTest {
        val repo = newRepo()
        val user = newUser()

        val result = repo.applyHandBatch(user, emptyList())

        assertEquals(0, result.stats.handsPlayed)
        assertTrue(result.appliedKeys.isEmpty())
        assertNotNull(repo.find(user), "the hydrate pulse still lazy-creates the row")
    }

    @Test
    fun applyHandBatch_keysAreScopedPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.applyHandBatch(a, listOf(hand(key = "shared")))
        val forB = repo.applyHandBatch(b, listOf(hand(key = "shared")))

        assertEquals(setOf("shared"), forB.appliedKeys, "another user's key is not a replay")
        assertEquals(1, forB.stats.handsPlayed)
    }

    /** The stored per-row `no_bust_streak`, keyed by idempotency key. */
    private fun streaksFor(user: UserId): Map<String, Long> = database.blockingTransaction {
        PlayerStatEventsTable
            .selectAll()
            .where { PlayerStatEventsTable.userId eq user.value }
            .associate { it[PlayerStatEventsTable.idempotencyKey] to it[PlayerStatEventsTable.noBustStreak] }
    }

    /**
     * A sequence chosen to move every shape of counter: accumulators, the
     * streak, both high-water marks, the per-bot family, and the short-stack
     * latch (armed by m4's 8 BB finish, fired by m6's 120 BB one).
     */
    private fun mixedHands() = listOf(
        hand(key = "m1", won = true, vsBot = true, beatenBotId = "Jane", potTotal = 400, bigBlind = 20),
        hand(key = "m2", folded = true, potTotal = 900, bigBlind = 20),
        hand(key = "m3", lostAtShowdown = true, busted = true, bigBlind = 20),
        hand(key = "m4", startStack = 300, endStack = 160, bigBlind = 20),
        hand(key = "m5", won = true, vsBot = true, beatenBotId = "Jane", startStack = 160, endStack = 500, bigBlind = 20),
        hand(key = "m6", won = true, wasAllIn = true, startStack = 500, endStack = 2_400, bigBlind = 20, potTotal = 3_000),
        hand(key = "m7", won = true, vsBot = true, beatenBotId = "Rex", botDifficulty = "Challenging", bustsDealt = 2),
        hand(key = "m8", handStrengthShown = ShownHand.Flush.name, potTotal = 120, bigBlind = 20),
    )

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

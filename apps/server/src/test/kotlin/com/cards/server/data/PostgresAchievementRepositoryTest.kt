package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.AchievementsEarnedTable
import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.deleteAll
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Integration tests for the Postgres-backed earned-achievement repo (real
 * Postgres via testcontainers). Covers the `(user_id, achievement_id)` PK
 * dedup (first-write-wins), per-user scoping, and the delete cascade hook.
 */
@OptIn(ExperimentalTime::class)
class PostgresAchievementRepositoryTest : DatabaseTest() {

    @After
    fun cleanTables() {
        database.blockingTransaction { AchievementsEarnedTable.deleteAll() }
    }

    @Test
    fun recordEarned_persists_andListsBack() = runTest {
        val repo = newRepo()
        val userId = newUser()

        repo.recordEarned(userId, "FIRST_WIN", at(1_000))

        val earned = repo.listEarned(userId)
        assertEquals(1, earned.size)
        assertEquals("FIRST_WIN", earned.first().achievementId)
        assertEquals(at(1_000), earned.first().earnedAt)
    }

    @Test
    fun recordEarned_isIdempotent_firstWriteWins() = runTest {
        val repo = newRepo()
        val userId = newUser()

        val first = repo.recordEarned(userId, "FIRST_WIN", at(1_000))
        val second = repo.recordEarned(userId, "FIRST_WIN", at(9_999))

        assertEquals(at(1_000), first.earnedAt)
        assertEquals(at(1_000), second.earnedAt, "re-earn keeps the original timestamp")
        assertEquals(1, repo.listEarned(userId).size, "no duplicate row")
    }

    @Test
    fun recordEarned_isScopedPerUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()

        repo.recordEarned(a, "SHARED_ID", at(1_000))
        repo.recordEarned(b, "SHARED_ID", at(2_000))

        assertEquals(1, repo.listEarned(a).size)
        assertEquals(1, repo.listEarned(b).size, "same id for two users is not a collision")
        assertEquals(at(2_000), repo.listEarned(b).first().earnedAt)
    }

    @Test
    fun deleteAllForUser_clears_andOnlyTheGivenUser() = runTest {
        val repo = newRepo()
        val a = newUser()
        val b = newUser()
        repo.recordEarned(a, "A1", at(1_000))
        repo.recordEarned(a, "A2", at(1_000))
        repo.recordEarned(b, "B1", at(1_000))

        repo.deleteAllForUser(a)

        assertTrue(repo.listEarned(a).isEmpty())
        assertEquals(listOf("B1"), repo.listEarned(b).map { it.achievementId })
    }

    private fun newRepo(): PostgresAchievementRepository =
        PostgresAchievementRepository(database = database)

    private fun newUser(): UserId = seedAuthUser()

    private fun at(epochMs: Long): Instant = Instant.fromEpochMilliseconds(epochMs)
}

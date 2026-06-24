package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.db.DatabaseTest
import com.dangerfield.cards.server.domain.BanReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * DB-backed tests for the native `auth.users.banned_until` read. Skips when
 * Docker is unavailable (see [DatabaseTest]).
 */
@OptIn(ExperimentalTime::class)
class PostgresModerationRepositoryTest : DatabaseTest() {

    private val now = Instant.parse("2026-06-24T12:00:00Z")
    private val fixedClock = object : Clock {
        override fun now(): Instant = now
    }

    private fun repo() = PostgresModerationRepository(database, fixedClock)

    @Test
    fun goodStanding_whenBannedUntilIsNull() = runTest {
        val user = seedAuthUser()
        assertNull(repo().banStatusFor(user))
    }

    @Test
    fun goodStanding_whenBannedUntilIsInThePast() = runTest {
        val user = seedAuthUser()
        setBannedUntil(user, (now - 1.hours).toJava())
        assertNull(repo().banStatusFor(user))
    }

    @Test
    fun banned_whenBannedUntilIsInTheFuture() = runTest {
        val user = seedAuthUser()
        val until = now + 24.hours
        setBannedUntil(user, until.toJava())
        val status = repo().banStatusFor(user)
        assertEquals(BanReason.Banned, status?.reason)
        assertEquals(until, status?.until)
    }

    @Test
    fun goodStanding_afterBanIsCleared() = runTest {
        val user = seedAuthUser()
        setBannedUntil(user, (now + 24.hours).toJava())
        setBannedUntil(user, null)
        assertNull(repo().banStatusFor(user))
    }

    private fun Instant.toJava(): java.time.Instant =
        java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())
}

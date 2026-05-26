package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class DefaultOrphanAnonymousSweepTest {

    private val u1 = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val u2 = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val u3 = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
    private val frozen = Instant.fromEpochSeconds(1_700_000_000)
    private val fixedClock = object : Clock { override fun now(): Instant = frozen }

    @Test
    fun run_deletesAllCandidates_onHappyPath() = runTest {
        val admin = FakeAdmin(candidates = listOf(u1, u2, u3))
        val profiles = FakeProfileRepository()
        val sweep = DefaultOrphanAnonymousSweep(admin, profiles, fixedClock)

        val result = sweep.run(maxInactiveAge = 30.days)

        assertEquals(3, result.candidatesFound)
        assertEquals(3, result.deleted)
        assertEquals(0, result.failedToDelete)
        assertEquals(false, result.notConfigured)
        assertEquals(listOf(u1, u2, u3), admin.deletedAdminUsers)
        assertEquals(listOf(u1, u2, u3), profiles.deletedProfileUsers)
    }

    @Test
    fun run_continuesSweep_whenProfileDeleteThrows() = runTest {
        val admin = FakeAdmin(candidates = listOf(u1, u2, u3))
        val profiles = FakeProfileRepository(failOn = setOf(u2))
        val sweep = DefaultOrphanAnonymousSweep(admin, profiles, fixedClock)

        val result = sweep.run(maxInactiveAge = 30.days)

        assertEquals(3, result.candidatesFound)
        assertEquals(3, result.deleted)
        assertEquals(0, result.failedToDelete)
        assertEquals(listOf(u1, u2, u3), profiles.deletedProfileUsers)
    }

    @Test
    fun run_propagatesCancellationException_fromProfileDelete() = runTest {
        val admin = FakeAdmin(candidates = listOf(u1, u2, u3))
        val profiles = FakeProfileRepository(cancelOn = setOf(u2))
        val sweep = DefaultOrphanAnonymousSweep(admin, profiles, fixedClock)

        assertFailsWith<CancellationException> {
            sweep.run(maxInactiveAge = 30.days)
        }
        assertTrue(u3 !in profiles.deletedProfileUsers, "sweep must abort once cancelled")
    }

    @Test
    fun run_reportsFailedDelete_whenAdminDeleteFails() = runTest {
        val admin = FakeAdmin(
            candidates = listOf(u1, u2),
            failureFor = mapOf(u1 to DeleteUserResult.Failure(statusCode = 500, cause = null)),
        )
        val profiles = FakeProfileRepository()
        val sweep = DefaultOrphanAnonymousSweep(admin, profiles, fixedClock)

        val result = sweep.run(maxInactiveAge = 30.days)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.failedToDelete)
        assertEquals(listOf(u2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_reportsNotConfigured_whenAdminProbeNotConfigured() = runTest {
        val admin = FakeAdmin(
            candidates = emptyList(),
            probeResult = DeleteUserResult.NotConfigured,
        )
        val profiles = FakeProfileRepository()
        val sweep = DefaultOrphanAnonymousSweep(admin, profiles, fixedClock)

        val result = sweep.run(maxInactiveAge = 30.days)

        assertEquals(0, result.candidatesFound)
        assertEquals(true, result.notConfigured)
    }

    private class FakeAdmin(
        private val candidates: List<UserId>,
        private val failureFor: Map<UserId, DeleteUserResult> = emptyMap(),
        private val probeResult: DeleteUserResult = DeleteUserResult.Success,
    ) : SupabaseAdminClient {
        val deletedAdminUsers = mutableListOf<UserId>()
        override suspend fun listAnonymousUsersOlderThan(olderThan: Instant): List<UserId> = candidates
        override suspend fun deleteUser(userId: UserId): DeleteUserResult {
            val zero = UserId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
            if (userId == zero) return probeResult
            failureFor[userId]?.let { return it }
            deletedAdminUsers += userId
            return DeleteUserResult.Success
        }
    }

    private class FakeProfileRepository(
        private val failOn: Set<UserId> = emptySet(),
        private val cancelOn: Set<UserId> = emptySet(),
    ) : ProfileRepository {
        val deletedProfileUsers = mutableListOf<UserId>()
        override suspend fun delete(userId: UserId) {
            deletedProfileUsers += userId
            if (userId in cancelOn) throw CancellationException("simulated cancellation")
            if (userId in failOn) error("simulated profile delete failure")
        }
        override suspend fun findOrCreate(userId: UserId): Profile = error("unused")
        override suspend fun findById(userId: UserId): Profile? = error("unused")
        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
    }
}

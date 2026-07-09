package com.dangerfield.cards.server.data

import com.dangerfield.cards.server.domain.AcquisitionSource
import com.dangerfield.cards.server.domain.DeleteUserResult
import com.dangerfield.cards.server.domain.Profile
import com.dangerfield.cards.server.domain.ProfileRepository
import com.dangerfield.cards.server.domain.SupabaseAdminClient
import com.dangerfield.cards.server.domain.UpdateProfileOutcome
import com.dangerfield.cards.server.domain.UserId
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class DefaultOrphanInstallSweepTest {

    private val install = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val caller = UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    private val sibling1 = UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"))
    private val sibling2 = UserId(UUID.fromString("33333333-3333-3333-3333-333333333333"))
    private val sibling3 = UserId(UUID.fromString("44444444-4444-4444-4444-444444444444"))

    @Test
    fun run_deletesAllVerifiedSiblings_whenInventoryIsStarterOnly() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val verifier = OrphanSweepFakes.verifier(
            owned = mapOf(
                sibling1 to OrphanSweepFakes.starterRows(),
                sibling2 to OrphanSweepFakes.starterRows() + OrphanSweepFakes.foundingMemberRow(),
            ),
        )
        val sweep = DefaultOrphanInstallSweep(profiles, admin, verifier)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(2, result.deleted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failedToDelete)
        assertEquals(false, result.notConfigured)
        assertEquals(listOf(sibling1, sibling2), admin.deletedAdminUsers)
        assertEquals(listOf(sibling1, sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_skipsCandidate_whenInventoryShowsEngagement() = runTest {
        // sibling1 owns a non-starter, non-founding row — earned via
        // gameplay or chip-purchased. The sweep refuses to delete them
        // even though the SQL gate accepted them.
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val verifier = OrphanSweepFakes.verifier(
            owned = mapOf(
                sibling1 to OrphanSweepFakes.starterRows() +
                    OrphanSweepFakes.ownedRow("felt_blue_velvet", AcquisitionSource.Purchased),
                sibling2 to OrphanSweepFakes.starterRows(),
            ),
        )
        val sweep = DefaultOrphanInstallSweep(profiles, admin, verifier)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped)
        assertEquals(listOf(sibling2), admin.deletedAdminUsers)
        assertEquals(listOf(sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_skipsCandidate_whenSittingInActiveRoom() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val verifier = OrphanSweepFakes.verifier(seatedUsers = setOf(sibling1))
        val sweep = DefaultOrphanInstallSweep(profiles, admin, verifier)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped)
        assertEquals(listOf(sibling2), admin.deletedAdminUsers)
    }

    @Test
    fun run_skipsCandidate_whenAboveLevel1() = runTest {
        // sibling1 has crossed into level 2 (>= 100 XP). Even though their
        // inventory is starter-only and they're not in a room, the sweep
        // preserves them — earned progress is never deleted.
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val verifier = OrphanSweepFakes.verifier(xpByUser = mapOf(sibling1 to 100L, sibling2 to 99L))
        val sweep = DefaultOrphanInstallSweep(profiles, admin, verifier)

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped)
        assertEquals(listOf(sibling2), admin.deletedAdminUsers)
        assertEquals(listOf(sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_skipsCandidate_withIapSpend() = runTest {
        // Belt-and-suspenders: the SQL gate already excludes IAP spenders,
        // but the shared verifier re-checks so a gate regression can never
        // delete a paying account.
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin()
        val verifier = OrphanSweepFakes.verifier(iapSpenders = setOf(sibling1))
        val sweep = DefaultOrphanInstallSweep(profiles, admin, verifier)

        val result = sweep.run(install, caller)

        assertEquals(1, result.deleted)
        assertEquals(1, result.skipped)
        assertEquals(listOf(sibling2), admin.deletedAdminUsers)
    }

    @Test
    fun run_recordsFailedDelete_whenAdminFails() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2))
        val admin = FakeAdmin(
            failureFor = mapOf(sibling1 to DeleteUserResult.Failure(statusCode = 500, cause = null)),
        )
        val sweep = DefaultOrphanInstallSweep(profiles, admin, OrphanSweepFakes.verifier())

        val result = sweep.run(install, caller)

        assertEquals(2, result.candidatesFound)
        assertEquals(1, result.deleted)
        assertEquals(1, result.failedToDelete)
        assertEquals(0, result.skipped)
        assertEquals(listOf(sibling2), profiles.deletedProfileUsers)
    }

    @Test
    fun run_shortCircuits_whenServiceRoleKeyNotConfigured() = runTest {
        val profiles = FakeProfileRepository(siblings = listOf(sibling1, sibling2, sibling3))
        val admin = FakeAdmin(defaultResult = DeleteUserResult.NotConfigured)
        val sweep = DefaultOrphanInstallSweep(profiles, admin, OrphanSweepFakes.verifier())

        val result = sweep.run(install, caller)

        assertEquals(3, result.candidatesFound)
        assertEquals(0, result.deleted)
        assertEquals(true, result.notConfigured)
        assertTrue(profiles.deletedProfileUsers.isEmpty(), "unconfigured admin must not produce any local deletes")
    }

    @Test
    fun run_returnsEmpty_whenNoSiblings() = runTest {
        val profiles = FakeProfileRepository(siblings = emptyList())
        val sweep = DefaultOrphanInstallSweep(profiles, FakeAdmin(), OrphanSweepFakes.verifier())

        val result = sweep.run(install, caller)

        assertEquals(0, result.candidatesFound)
        assertEquals(0, result.deleted)
        assertEquals(0, result.skipped)
        assertEquals(0, result.failedToDelete)
        assertEquals(false, result.notConfigured)
    }

    // ---------- fakes ----------

    private class FakeProfileRepository(
        private val siblings: List<UserId>,
    ) : ProfileRepository {
        val deletedProfileUsers: MutableList<UserId> = mutableListOf()

        override suspend fun findInstallSiblings(
            installId: UUID,
            currentUserId: UserId,
        ): List<UserId> = siblings

        override suspend fun delete(userId: UserId) {
            deletedProfileUsers += userId
        }

        override suspend fun findOrCreate(userId: UserId): Profile = error("unused")
        override suspend fun findById(userId: UserId): Profile? = null
        override suspend fun update(
            userId: UserId,
            displayName: String?,
            avatarEmoji: String?,
            avatarBackgroundColor: String?,
            clearAvatarBackgroundColor: Boolean,
        ): UpdateProfileOutcome = error("unused")
        override suspend fun touchInstallId(userId: UserId, installId: UUID): UUID? = error("unused")
    }

    private class FakeAdmin(
        private val failureFor: Map<UserId, DeleteUserResult> = emptyMap(),
        private val defaultResult: DeleteUserResult = DeleteUserResult.Success,
    ) : SupabaseAdminClient {
        val deletedAdminUsers: MutableList<UserId> = mutableListOf()

        override suspend fun deleteUser(userId: UserId): DeleteUserResult {
            failureFor[userId]?.let { return it }
            if (defaultResult is DeleteUserResult.Success || defaultResult is DeleteUserResult.AlreadyGone) {
                deletedAdminUsers += userId
            }
            return defaultResult
        }

        override suspend fun listAnonymousUsersOlderThan(olderThan: Instant): List<UserId> = emptyList()
    }
}
